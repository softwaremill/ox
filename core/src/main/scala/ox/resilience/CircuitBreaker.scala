package ox.resilience

import scala.concurrent.duration.*
import ox.*
import java.util.concurrent.TimeUnit
import ox.scheduling.scheduled
import ox.scheduling.{ScheduledConfig, Schedule}
import java.util.concurrent.Semaphore
import ox.channels.Actor
import ox.channels.BufferCapacity
import ox.channels.ActorRef
import scala.util.Try

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
    operationsInWindow: Int,
    lastAcquisitionResult: Option[AcquireResult]
)

enum SlidingWindow:
  case CountBased(windowSize: Int)
  case TimeBased(duration: FiniteDuration)

case class CircuitBreakerConfig(
    failureRateThreshold: Int = 50,
    slowCallThreshold: Int = 0,
    slowCallDurationThreshold: FiniteDuration = 60.seconds,
    slidingWindow: SlidingWindow = SlidingWindow.CountBased(100),
    minimumNumberOfCalls: Int = 100,
    waitDurationOpenState: FiniteDuration = FiniteDuration(10, TimeUnit.SECONDS),
    halfOpenTimeoutDuration: FiniteDuration = FiniteDuration(0, TimeUnit.MILLISECONDS),
    numberOfCallsInHalfOpenState: Int = 10
):
  assert(
    failureRateThreshold >= 0 && failureRateThreshold <= 100,
    s"failureRateThreshold must be between 0 and 100, value: $failureRateThreshold"
  )
  assert(
    slowCallThreshold >= 0 && slowCallThreshold <= 100,
    s"slowCallThreshold must be between 0 and 100, value: $slowCallThreshold"
  )
end CircuitBreakerConfig

private case class AcquireResult(acquired: Boolean, circuitState: CircuitBreakerState)

private case class CircuitBreakerStateMachineConfig(
    failureRateThreshold: Int,
    slowCallThreshold: Int,
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

class CircuitBreaker(val config: CircuitBreakerConfig)(using Ox):
  val stateMachine = CircuitBreakerStateMachine(config)
  private val actorRef: ActorRef[CircuitBreakerStateMachine] = Actor.create(stateMachine)(using sc = BufferCapacity.apply(100))

  private def tryAcquire: AcquireResult = stateMachine.state match
    case CircuitBreakerState.Closed                                => AcquireResult(true, CircuitBreakerState.Closed)
    case currState @ CircuitBreakerState.Open(_)                   => AcquireResult(false, currState)
    case currState @ CircuitBreakerState.HalfOpen(_, semaphore, _) => AcquireResult(semaphore.tryAcquire(1), currState)

  def runOrDropWithErrorMode[E, F[_], T](em: ErrorMode[E, F], resultPolicy: ResultPolicy[E, T] = ResultPolicy.default[E, T])(
      operation: => F[T]
  ): Option[F[T]] =
    val acquiredResult = tryAcquire
    if acquiredResult.acquired then
      val before = System.nanoTime()
      val result = operation
      val after = System.nanoTime()
      val duration = (after - before).nanos
      // Check result and results of policy
      if em.isError(result) && resultPolicy.isWorthRetrying(em.getError(result)) then
        actorRef.tell(_.registerResult(CircuitBreakerResult.Failure, acquiredResult, actorRef))
        Some(result)
      else if resultPolicy.isSuccess(em.getT(result)) then
        if duration > config.slowCallDurationThreshold then
          actorRef.tell(_.registerResult(CircuitBreakerResult.Slow, acquiredResult, actorRef))
        else actorRef.tell(_.registerResult(CircuitBreakerResult.Success, acquiredResult, actorRef))
        Some(result)
      else
        actorRef.tell(_.registerResult(CircuitBreakerResult.Failure, acquiredResult, actorRef))
        Some(result)
      end if
    else None
    end if
  end runOrDropWithErrorMode

  def runOrDropEither[E, T](resultPolicy: ResultPolicy[E, T] = ResultPolicy.default[E, T])(
      operation: => Either[E, T]
  ): Option[Either[E, T]] =
    runOrDropWithErrorMode(EitherMode[E], resultPolicy)(operation)

  def runOrDrop[T](resultPolicy: ResultPolicy[Throwable, T] = ResultPolicy.default[Throwable, T])(operation: => T): Option[T] =
    runOrDropEither(resultPolicy)(Try(operation).toEither).map(_.fold(throw _, identity))
end CircuitBreaker

private sealed trait CircuitBreakerStateMachine(val config: CircuitBreakerStateMachineConfig)(using val ox: Ox):
  def calculateMetrics(lastAcquisitionResult: Option[AcquireResult]): Metrics
  def updateResults(result: CircuitBreakerResult): Unit

  @volatile private var _state: CircuitBreakerState = CircuitBreakerState.Closed

  def state: CircuitBreakerState = _state

  def registerResult(result: CircuitBreakerResult, acquired: AcquireResult, selfRef: ActorRef[CircuitBreakerStateMachine]): Unit =
    updateResults(result)
    val oldState = _state
    val newState = nextState(calculateMetrics(Some(acquired)), oldState, System.currentTimeMillis())
    _state = newState
    scheduleCallback(oldState, newState, selfRef)
  end registerResult

  def updateState(): Unit =
    _state = nextState(calculateMetrics(None), _state, System.currentTimeMillis())

  private def scheduleCallback(
      oldState: CircuitBreakerState,
      newState: CircuitBreakerState,
      selfRef: ActorRef[CircuitBreakerStateMachine]
  ): Unit =
    (oldState, newState) match
      case (CircuitBreakerState.Closed, CircuitBreakerState.Open(_)) =>
        // schedule switch to halfOpen after timeout
        updateAfter(config.waitDurationOpenState, selfRef)
      case (CircuitBreakerState.Open(_), CircuitBreakerState.HalfOpen(since, semaphore, completedOperations)) =>
        // schedule timeout for halfOpen state
        updateAfter(config.halfOpenTimeoutDuration, selfRef)
      case _ => ()

  private def updateAfter(after: FiniteDuration, actorRef: ActorRef[CircuitBreakerStateMachine])(using Ox): Unit =
    forkDiscard:
      scheduled(ScheduledConfig(Schedule.InitialDelay(after)))(actorRef.tell(_.updateState()))

  private def nextState(metrics: Metrics, currentState: CircuitBreakerState, currentTimestamp: Long): CircuitBreakerState =
    val lastAcquireResult = metrics.lastAcquisitionResult.filter(_.acquired)
    val exceededThreshold = (metrics.failureRate >= config.failureRateThreshold || metrics.slowCallsRate >= config.slowCallThreshold)
    val minCallsRecorder = metrics.operationsInWindow >= config.minimumNumberOfCalls
    currentState match
      case CircuitBreakerState.Closed =>
        if minCallsRecorder && exceededThreshold then
          if config.waitDurationOpenState.toMillis != 0 then
            CircuitBreakerState.HalfOpen(currentTimestamp, Semaphore(config.numberOfCallsInHalfOpenState))
          else CircuitBreakerState.Open(currentTimestamp)
        else CircuitBreakerState.Closed
      case CircuitBreakerState.Open(since) =>
        val timePassed = (currentTimestamp - since) > config.waitDurationOpenState.toMillis
        if timePassed then CircuitBreakerState.HalfOpen(currentTimestamp, Semaphore(config.numberOfCallsInHalfOpenState))
        else CircuitBreakerState.Open(since)
      case CircuitBreakerState.HalfOpen(since, semaphore, completedCalls) =>
        lazy val allCallsInHalfOpenCompleted = completedCalls >= config.numberOfCallsInHalfOpenState
        lazy val timePassed = (currentTimestamp - since) > config.halfOpenTimeoutDuration.toMillis
        // if we didn't complete all half open calls but timeout is reached go back to open
        if !allCallsInHalfOpenCompleted && config.halfOpenTimeoutDuration.toMillis != 0 && timePassed then
          CircuitBreakerState.Open(currentTimestamp)
        // If halfOpen calls were completed && rates are below we close breaker
        else if allCallsInHalfOpenCompleted && !exceededThreshold then CircuitBreakerState.Closed
        // If halfOpen calls completed, but rates are still above go back to open
        else if allCallsInHalfOpenCompleted && exceededThreshold then CircuitBreakerState.Open(currentTimestamp)
        // We didn't complete all half open calls, keep halfOpen
        else
          lastAcquireResult match
            case Some(AcquireResult(true, CircuitBreakerState.HalfOpen(s, sem, completed))) =>
              CircuitBreakerState.HalfOpen(s, sem, completed + 1)
            case _ => CircuitBreakerState.HalfOpen(since, semaphore, completedCalls)
        end if
    end match
  end nextState
end CircuitBreakerStateMachine

private[resilience] object CircuitBreakerStateMachine:
  def apply(config: CircuitBreakerConfig)(using
      Ox
  ): CircuitBreakerStateMachine =
    config.slidingWindow match
      case SlidingWindow.CountBased(size) =>
        CircuitBreakerCountStateMachine(
          CircuitBreakerStateMachineConfig.fromConfig(config),
          size
        )
      case SlidingWindow.TimeBased(duration) =>
        CircuitBreakerTimeStateMachine(
          CircuitBreakerStateMachineConfig.fromConfig(config),
          duration
        )
  end apply

  private[resilience] case class CircuitBreakerCountStateMachine(
      stateMachineConfig: CircuitBreakerStateMachineConfig,
      windowSize: Int
  )(using ox: Ox)
      extends CircuitBreakerStateMachine(stateMachineConfig)(using ox):

    private val callResults: Array[Option[CircuitBreakerResult]] = Array.fill[Option[CircuitBreakerResult]](windowSize)(None)
    private var writeIndex = 0

    def updateResults(result: CircuitBreakerResult): Unit =
      callResults(writeIndex) = Some(result)
      writeIndex = (writeIndex + 1) % windowSize

    def calculateMetrics(lastAcquisitionResult: Option[AcquireResult]): Metrics =
      val results = callResults.flatMap(identity)
      val numOfOperations = results.length
      val failuresRate = ((results.count(_ == CircuitBreakerResult.Failure) / windowSize.toFloat) * 100).toInt
      val slowRate = ((results.count(_ == CircuitBreakerResult.Slow) / windowSize.toFloat) * 100).toInt
      Metrics(
        failuresRate,
        slowRate,
        numOfOperations,
        lastAcquisitionResult
      )
    end calculateMetrics
  end CircuitBreakerCountStateMachine

  private[resilience] case class CircuitBreakerTimeStateMachine(
      stateMachineConfig: CircuitBreakerStateMachineConfig,
      windowDuration: FiniteDuration
  )(using ox: Ox)
      extends CircuitBreakerStateMachine(stateMachineConfig)(using ox):
    def calculateMetrics(lastAcquisitionResult: Option[AcquireResult]): Metrics = ???
    def updateResults(result: CircuitBreakerResult): Unit = ???
  end CircuitBreakerTimeStateMachine
end CircuitBreakerStateMachine
