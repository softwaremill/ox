package ox.resilience

import scala.concurrent.duration.*
import ox.*
import java.util.concurrent.Semaphore
import ox.channels.Actor
import ox.channels.BufferCapacity
import ox.channels.ActorRef
import scala.util.Try

private[resilience] enum CircuitBreakerState:
  case Open(since: Long)
  case Closed(since: Long)
  case HalfOpen(since: Long, semaphore: Semaphore, completedOperations: Int = 0)
  def isSameState(other: CircuitBreakerState): Boolean =
    (this, other) match
      case (Open(sinceOpen), Open(since)) if since == sinceOpen                             => true
      case (HalfOpen(sinceHalfOpen, _, _), HalfOpen(since, _, _)) if sinceHalfOpen == since => true
      case (Closed(sinceClosed), Closed(since)) if sinceClosed == since                     => true
      case _                                                                                => false
end CircuitBreakerState

private[resilience] enum CircuitBreakerResult:
  case Success
  case Failure
  case Slow

private[resilience] case class Metrics(
    failurePercentage: Int,
    slowCallsPercentage: Int,
    operationsInWindow: Int,
    lastAcquisitionResult: Option[AcquireResult],
    timestamp: Long
)

private[resilience] case class AcquireResult(acquired: Boolean, circuitState: CircuitBreakerState)

private case class CircuitBreakerStateMachineConfig(
    failureRateThreshold: PercentageThreshold,
    slowCallThreshold: PercentageThreshold,
    slowCallDurationThreshold: FiniteDuration,
    minimumNumberOfCalls: Int,
    numberOfCallsInHalfOpenState: Int,
    waitDurationOpenState: FiniteDuration,
    halfOpenTimeoutDuration: FiniteDuration
)
private object CircuitBreakerStateMachineConfig:
  def fromConfig(c: CircuitBreakerConfig): CircuitBreakerStateMachineConfig =
    CircuitBreakerStateMachineConfig(
      failureRateThreshold = c.failureRateThreshold,
      slowCallThreshold = c.slowCallThreshold,
      slowCallDurationThreshold = c.slowCallDurationThreshold,
      minimumNumberOfCalls = c.minimumNumberOfCalls,
      numberOfCallsInHalfOpenState = c.numberOfCallsInHalfOpenState,
      waitDurationOpenState = c.waitDurationOpenState,
      halfOpenTimeoutDuration = c.halfOpenTimeoutDuration
    )
end CircuitBreakerStateMachineConfig

/** Circuit Breaker. Operations can be dropped, when the breaker is open or if it doesn't take more operation in halfOpen state. The Circuit
  * Breaker might calculate different metrics based on [[SlidingWindow]] provided in config. See [[SlidingWindow]] for more details.
  */
case class CircuitBreaker(config: CircuitBreakerConfig = CircuitBreakerConfig.default)(using ox: Ox, bufferCapacity: BufferCapacity):
  private[resilience] val stateMachine = CircuitBreakerStateMachine(config)
  private val actorRef: ActorRef[CircuitBreakerStateMachine] = Actor.create(stateMachine)

  private def tryAcquire(): AcquireResult = stateMachine.state match
    case currState @ CircuitBreakerState.Closed(_)                 => AcquireResult(true, currState)
    case currState @ CircuitBreakerState.Open(_)                   => AcquireResult(false, currState)
    case currState @ CircuitBreakerState.HalfOpen(_, semaphore, _) => AcquireResult(semaphore.tryAcquire(1), currState)

  /** Runs the operation using the given error mode or drops it if the breaker is open.
    * @param em
    *   The error mode to use, which specifies when a result value is considered success, and when a failure.
    * @param operation
    *   The operation to run.
    * @return
    *   `Some` if the operation has been run, `None` if the operation has been dropped.
    */
  def runOrDropWithErrorMode[E, F[_], T](em: ErrorMode[E, F])(
      operation: => F[T]
  ): Option[F[T]] =
    val acquiredResult = tryAcquire()
    if acquiredResult.acquired then
      val (duration, result) = timed(operation)
      if em.isError(result) then
        actorRef.tell(_.registerResult(CircuitBreakerResult.Failure, acquiredResult, actorRef))
        Some(result)
      else
        if duration > config.slowCallDurationThreshold then
          actorRef.tell(_.registerResult(CircuitBreakerResult.Slow, acquiredResult, actorRef))
        else actorRef.tell(_.registerResult(CircuitBreakerResult.Success, acquiredResult, actorRef))
        Some(result)
      end if
    else None
    end if
  end runOrDropWithErrorMode

  /** Runs the operation returning [[scala.util.Either]] or drops it if the breaker is open. Note that any exceptions thrown by the
    * operation aren't caught and are propagated to user.
    *
    * @param operation
    *   The operation to run.
    * @return
    *   `Some` if the operation has been run, `None` if the operation has been dropped.
    * @throws anything
    *   The exception thrown by operation.
    */
  def runOrDropEither[E, T](
      operation: => Either[E, T]
  ): Option[Either[E, T]] =
    runOrDropWithErrorMode(EitherMode[E])(operation)

  /** Runs the operation or drops it if the breaker is open returning a direct result wrapped in [[Option]]
    *
    * @param operation
    *   The operation to run.
    * @return
    *   `Some` if the operation has been run, `None` if the operation has been dropped.
    * @throws anything
    *   The exception thrown by operation.
    */
  def runOrDrop[T](operation: => T): Option[T] =
    runOrDropEither(Try(operation).toEither).map(_.fold(throw _, identity))
end CircuitBreaker

object CircuitBreaker:
  given default: BufferCapacity = BufferCapacity.apply(100)
