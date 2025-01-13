package ox.resilience

import scala.concurrent.duration.*
import java.util.concurrent.atomic.AtomicReference
import ox.*
import java.util.concurrent.TimeUnit
import ox.scheduling.scheduled
import ox.scheduling.{ScheduledConfig, Schedule}
import java.util.concurrent.Semaphore
import ox.channels.Actor
import ox.channels.BufferCapacity
import ox.channels.ActorRef

enum CircuitBreakerState:
  case Open(since: Long)
  case Closed
  case HalfOpen(since: Long, semaphore: Semaphore, completedOperations: Int = 0)

enum CircuitBreakerResult:
  case Success
  case Failure
  case Slow

case class Metrics(
    failureRate: Int,
    slowCallsRate: Int,
    operationsInWindow: Int
)

enum SlidingWindow:
  case CountBased(windowSize: Int)
  case TimeBased(duration: FiniteDuration)

// TODO -- missing params maxWaitDurationInHalfOpenState - timeout to complete enough operations in HalfOpen state, otherwise go back to open
case class CircuitBreakerConfig(
    failureRateThreshold: Int = 50,
    slowCallThreshold: Int = 0,
    slowCallDurationThreshold: FiniteDuration = 60.seconds,
    slidingWindow: SlidingWindow = SlidingWindow.CountBased(100),
    minimumNumberOfCalls: Int = 100,
    waitDurationOpenState: FiniteDuration = FiniteDuration(0, TimeUnit.MILLISECONDS),
    halfOpenTimeoutDuration: FiniteDuration = FiniteDuration(0, TimeUnit.MILLISECONDS),
    numberOfCallsInHalfOpenState: Int = 10
)

private case class AcquireResult(acquired: Boolean, circuitState: CircuitBreakerState)

private case class CircuitBreakerStateMachineConfig(
    failureRateThreshold: Int,
    slowCallThreshold: Int,
    slowCallDurationThreshold: FiniteDuration,
    minimumNumberOfCalls: Int,
    numberOfCallsInHalfOpenState: Int,
    waitDurationOpenState: FiniteDuration,
    halfOpenTimeoutDuration: FiniteDuration,
    state: AtomicReference[CircuitBreakerState]
)
private object CircuitBreakerStateMachineConfig:
  def fromConfig(c: CircuitBreakerConfig, state: AtomicReference[CircuitBreakerState]): CircuitBreakerStateMachineConfig =
    CircuitBreakerStateMachineConfig(
      failureRateThreshold = c.failureRateThreshold,
      slowCallThreshold = c.slowCallThreshold,
      slowCallDurationThreshold = c.slowCallDurationThreshold,
      minimumNumberOfCalls = c.minimumNumberOfCalls,
      numberOfCallsInHalfOpenState = c.numberOfCallsInHalfOpenState,
      waitDurationOpenState = c.waitDurationOpenState,
      halfOpenTimeoutDuration = c.halfOpenTimeoutDuration,
      state = state
    )
end CircuitBreakerStateMachineConfig

class CircuitBreaker(val config: CircuitBreakerConfig)(using Ox):
  private val state = AtomicReference[CircuitBreakerState](CircuitBreakerState.Closed)
  private val actorRef: ActorRef[CircuitBreakerStateMachine] = Actor.create(CircuitBreakerStateMachine(config, state))

  private def tryAcquire: AcquireResult = state.get match
    case CircuitBreakerState.Closed                                => AcquireResult(true, CircuitBreakerState.Closed)
    case currState @ CircuitBreakerState.Open(_)                   => AcquireResult(false, currState)
    case currState @ CircuitBreakerState.HalfOpen(_, semaphore, _) => AcquireResult(semaphore.tryAcquire(1), currState)

    // TODO - register schedule for timeouts
  def runOrDrop[E, F[_], T](em: ErrorMode[E, F], resultPolicy: ResultPolicy[E, T] = ResultPolicy.default[E, T])(op: => F[T]): Option[F[T]] =
    val acquiredResult = tryAcquire
    if acquiredResult.acquired then
      val before = System.nanoTime()
      val result = op
      val after = System.nanoTime()
      val duration = (after - before).nanos
      // Check result and results of policy
      if em.isError(result) && resultPolicy.isWorthRetrying(em.getError(result)) then
        actorRef.tell(_.registerResult(CircuitBreakerResult.Failure, acquiredResult))
        Some(result)
      else if resultPolicy.isSuccess(em.getT(result)) then
        if duration > config.slowCallDurationThreshold then actorRef.tell(_.registerResult(CircuitBreakerResult.Slow, acquiredResult))
        else actorRef.tell(_.registerResult(CircuitBreakerResult.Success, acquiredResult))
        Some(result)
      else
        actorRef.tell(_.registerResult(CircuitBreakerResult.Failure, acquiredResult))
        Some(result)
      end if
    else None
    end if
  end runOrDrop

  def runEitherOrDrop[E, T](resultPolicy: ResultPolicy[E, T] = ResultPolicy.default[E, T])(
      op: => Either[E, T]
  ): Option[Either[E, T]] =
    val em = EitherMode[E]
    val acquiredResult = tryAcquire
    if acquiredResult.acquired then
      val before = System.nanoTime()
      val result = op
      val after = System.nanoTime()
      val duration = (after - before).nanos
      // Check result and results of policy
      if em.isError(result) && resultPolicy.isWorthRetrying(em.getError(result)) then
        actorRef.tell(_.registerResult(CircuitBreakerResult.Failure, acquiredResult))
        Some(result)
      else if resultPolicy.isSuccess(em.getT(result)) then
        if duration > config.slowCallDurationThreshold then actorRef.tell(_.registerResult(CircuitBreakerResult.Slow, acquiredResult))
        else actorRef.tell(_.registerResult(CircuitBreakerResult.Success, acquiredResult))
        Some(result)
      else
        actorRef.tell(_.registerResult(CircuitBreakerResult.Failure, acquiredResult))
        Some(result)
      end if
    else None
    end if
  end runEitherOrDrop
end CircuitBreaker

private sealed trait CircuitBreakerStateMachine:
  def registerResult(result: CircuitBreakerResult, acquired: AcquireResult): Unit
  def updateState(): Unit
  def calculateMetrics(results: Array[CircuitBreakerResult]): Metrics
//  def selfRef: ActorRef[CircuitBreakerStateMachine]

private[resilience] object CircuitBreakerStateMachine:
  def apply(config: CircuitBreakerConfig, state: AtomicReference[CircuitBreakerState])(using
      Ox
  ): CircuitBreakerStateMachine =
    config.slidingWindow match
      case SlidingWindow.CountBased(size) =>
        CircuitBreakerCountStateMachine(
          CircuitBreakerStateMachineConfig.fromConfig(config, state),
          size
        )
      case SlidingWindow.TimeBased(duration) =>
        CircuitBreakerTimeStateMachine(
          CircuitBreakerStateMachineConfig.fromConfig(config, state),
          duration
        )
  end apply

  private[resilience] case class CircuitBreakerCountStateMachine(
      config: CircuitBreakerStateMachineConfig,
      windowSize: Int
  )(using Ox)
      extends CircuitBreakerStateMachine:
    assert(
      config.failureRateThreshold >= 0 && config.failureRateThreshold <= 100,
      s"failureRateThreshold must be between 0 and 100, value: ${config.failureRateThreshold}"
    )
    assert(
      config.slowCallThreshold >= 0 && config.slowCallThreshold <= 100,
      s"slowCallThreshold must be between 0 and 100, value: ${config.slowCallThreshold}"
    )

    private val callResults: AtomicCircularBuffer[CircuitBreakerResult] = AtomicCircularBuffer[CircuitBreakerResult](windowSize)

    def registerResult(result: CircuitBreakerResult, acquired: AcquireResult): Unit =
      callResults.push(result)
      // In case of result coming from halfOpen state we update num of completed operation in this state if it didn't change
      if acquired.circuitState == CircuitBreakerState.HalfOpen then
        config.state.updateAndGet {
          case CircuitBreakerState.HalfOpen(since, semaphore, completedOperations) =>
            CircuitBreakerState.HalfOpen(since, semaphore, completedOperations + 1)
          case state => state
        }.discard

      config.state.set(nextState)
    end registerResult

    def updateState(): Unit =
      config.state.set(nextState)

    def calculateMetrics(results: Array[CircuitBreakerResult]): Metrics =
      val numOfOperations = results.length
      val failuresRate = ((results.count(_ == CircuitBreakerResult.Failure) / windowSize.toFloat) * 100).toInt
      val slowRate = ((results.count(_ == CircuitBreakerResult.Slow) / windowSize.toFloat) * 100).toInt
      Metrics(
        failuresRate,
        slowRate,
        numOfOperations
      )
    end calculateMetrics

    private def nextState: CircuitBreakerState =
      val metrics = calculateMetrics(callResults.snapshot)
      val exceededThreshold = (metrics.failureRate >= config.failureRateThreshold || metrics.slowCallsRate >= config.slowCallThreshold)
      val minCallsRecorder = metrics.operationsInWindow >= config.minimumNumberOfCalls
      config.state.get match
        case CircuitBreakerState.Closed =>
          if minCallsRecorder && exceededThreshold then
            // schedule switch to halfOpen after timeout
//            forkDiscard:
//              scheduled(ScheduledConfig(Schedule.InitialDelay(config.waitDurationOpenState)))(selfRef.tell(_.updateState()))
            CircuitBreakerState.Open(System.currentTimeMillis())
          else CircuitBreakerState.Closed
        case CircuitBreakerState.Open(since) =>
          val timePassed = (System.currentTimeMillis() - since) > config.waitDurationOpenState.toMillis
          if timePassed then CircuitBreakerState.HalfOpen(System.currentTimeMillis(), Semaphore(config.numberOfCallsInHalfOpenState))
          else CircuitBreakerState.Open(since)
        case CircuitBreakerState.HalfOpen(since, semaphore, completedCalls) =>
          lazy val timePassed = (System.currentTimeMillis() - since) > config.halfOpenTimeoutDuration.toMillis
          // if we didn't complete all half open calls but timeout is reached go back to open
          if !minCallsRecorder && config.halfOpenTimeoutDuration.toMillis != 0 && timePassed then
            // schedule timeout for halfOpen state
//            forkDiscard:
//              scheduled(ScheduledConfig(Schedule.InitialDelay(config.halfOpenTimeoutDuration)))(selfRef.tell(_.updateState()))
            CircuitBreakerState.Open(System.currentTimeMillis())
          // If halfOpen calls were completed && rates are below we close breaker
          else if minCallsRecorder && !exceededThreshold then CircuitBreakerState.Open(System.currentTimeMillis())
          // If halfOpen calls completed, but rates are still above go back to open
          else if minCallsRecorder && exceededThreshold
          then CircuitBreakerState.Open(System.currentTimeMillis())
          // We didn't complete all half open calls, keep halfOpen
          else CircuitBreakerState.HalfOpen(since, semaphore, completedCalls)
          end if
      end match
    end nextState
  end CircuitBreakerCountStateMachine

  private[resilience] case class CircuitBreakerTimeStateMachine(
      config: CircuitBreakerStateMachineConfig,
      windowDuration: FiniteDuration
  )(using Ox)
      extends CircuitBreakerStateMachine:
    def registerResult(result: CircuitBreakerResult, acquired: AcquireResult): Unit = ???
    def updateState(): Unit = ???
    def calculateMetrics(results: Array[CircuitBreakerResult]): Metrics = ???
  end CircuitBreakerTimeStateMachine
end CircuitBreakerStateMachine
