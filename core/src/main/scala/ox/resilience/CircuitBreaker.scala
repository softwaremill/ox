package ox.resilience

import scala.concurrent.duration.*
import java.util.concurrent.atomic.AtomicReference
import ox.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.TimeUnit
import ox.scheduling.scheduled
import ox.scheduling.{ScheduledConfig, Schedule}

enum CircuitBreakerState:
  case Open(since: Long)
  case Closed
  case HalfOpen(since: Long)

enum CircuitBreakerResult:
  case Success
  case Failure
  case Slow

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
    waitDurationOpenState: FiniteDuration = FiniteDuration.apply(0, TimeUnit.MILLISECONDS),
    numberOfCallsInHalfOpenState: Int
)

case class AcquireResult(acquired: Boolean, circuitState: CircuitBreakerState)

class CircuitBreaker(val config: CircuitBreakerConfig)(using Ox):
  private val stateMachine: CircuitBreakerStateMachine = CircuitBreakerStateMachine(config)
  private val slowCallDurationThreshold = config.slowCallDurationThreshold

  def state: CircuitBreakerState = stateMachine.getState

  def runOrDrop[E, F[_], T](em: ErrorMode[E, F])(resultPolicy: ResultPolicy[E, T] = ResultPolicy.default[E, T])(op: => F[T]): Option[F[T]] =
    val acquiredResult = stateMachine.tryAcquire
    if acquiredResult.acquired then
      val before = System.nanoTime()
      val result = op
      val after = System.nanoTime()
      val duration = (after - before).nanos
      // Check result and results of policy
      if em.isError(result) && resultPolicy.isWorthRetrying(em.getError(result)) then
        stateMachine.registerResult(CircuitBreakerResult.Failure, acquiredResult)
        Some(result)
      else if resultPolicy.isSuccess(em.getT(result)) then
        if duration > slowCallDurationThreshold then stateMachine.registerResult(CircuitBreakerResult.Slow, acquiredResult)
        else stateMachine.registerResult(CircuitBreakerResult.Success, acquiredResult)
        Some(result)
      else
        stateMachine.registerResult(CircuitBreakerResult.Failure, acquiredResult)
        Some(result)
      end if
    else None
    end if
  end runOrDrop
end CircuitBreaker

private sealed trait CircuitBreakerStateMachine:
  def getState: CircuitBreakerState
  def registerResult(result: CircuitBreakerResult, acquired: AcquireResult): Unit
  def tryAcquire: AcquireResult

private[resilience] object CircuitBreakerStateMachine:
  def apply(config: CircuitBreakerConfig)(using Ox): CircuitBreakerStateMachine =
    config.slidingWindow match
      case SlidingWindow.CountBased(size) =>
        CircuitBreakerCountStateMachine(
          size,
          config.failureRateThreshold,
          config.slowCallThreshold,
          config.slowCallDurationThreshold,
          config.minimumNumberOfCalls,
          config.numberOfCallsInHalfOpenState,
          config.waitDurationOpenState
        )
      case SlidingWindow.TimeBased(duration) =>
        CircuitBreakerTimeStateMachine(
          config.failureRateThreshold,
          config.slowCallThreshold,
          config.slowCallDurationThreshold,
          duration,
          config.minimumNumberOfCalls,
          config.numberOfCallsInHalfOpenState,
          config.waitDurationOpenState
        )
  end apply

  private[resilience] case class CircuitBreakerCountStateMachine(
      windowSize: Int,
      failureRateThreshold: Int,
      slowCallThreshold: Int,
      slowCallDurationThreshold: FiniteDuration,
      minimumNumberOfCalls: Int,
      numberOfCallsInHalfOpenState: Int,
      waitDurationOpenState: FiniteDuration
  )(using Ox)
      extends CircuitBreakerStateMachine:
    assert(
      failureRateThreshold >= 0 && failureRateThreshold <= 1,
      s"failureRateThreshold must be between 0 and 100, value: $failureRateThreshold"
    )
    assert(slowCallThreshold >= 0 && slowCallThreshold <= 1, s"slowCallThreshold must be between 0 and 100, value: $slowCallThreshold")

    private val state: AtomicReference[CircuitBreakerState] = AtomicReference(CircuitBreakerState.Closed)
    private val callResults: AtomicCircularBuffer[CircuitBreakerResult] = AtomicCircularBuffer[CircuitBreakerResult](windowSize)
    private val halfOpenTokens: TokenBucket = TokenBucket(numberOfCallsInHalfOpenState, Some(numberOfCallsInHalfOpenState))
    private val halfOpenNumOfCalls: AtomicInteger = AtomicInteger(0)

    def registerResult(result: CircuitBreakerResult, acquired: AcquireResult): Unit =
      callResults.push(result)
      if acquired.acquired && acquired.circuitState == CircuitBreakerState.HalfOpen then
        halfOpenTokens.release(1)
        halfOpenNumOfCalls.incrementAndGet().discard
      state.set(nextState(callResults.snapshot))

    def tryAcquire: AcquireResult = getState match
      case CircuitBreakerState.Closed                      => AcquireResult(true, CircuitBreakerState.Closed)
      case currState @ CircuitBreakerState.Open(since)     => AcquireResult(false, currState)
      case currState @ CircuitBreakerState.HalfOpen(since) => AcquireResult(halfOpenTokens.tryAcquire(1), currState)

    private def nextState(results: Array[CircuitBreakerResult]): CircuitBreakerState =
      val numOfOperations = results.size
      val failuresRate = ((results.count(_ == CircuitBreakerResult.Failure) / windowSize.toFloat) * 100).toInt
      val slowRate = ((results.count(_ == CircuitBreakerResult.Slow) / windowSize.toFloat) * 100).toInt
      state.updateAndGet { current =>
        current match
          // After operation check if we didn't cross thresholds
          case CircuitBreakerState.Closed =>
            if numOfOperations >= minimumNumberOfCalls && (failuresRate >= failureRateThreshold || slowRate >= slowCallThreshold) then
              // Start schedule to switch to HalfOpen after waitDurationOpenState passed
              forkDiscard(
                scheduled(ScheduledConfig[Throwable, Unit](Schedule.InitialDelay(waitDurationOpenState)))(
                  state.set(CircuitBreakerState.HalfOpen(System.currentTimeMillis()))
                )
              )
              CircuitBreakerState.Open(System.currentTimeMillis())
            else CircuitBreakerState.Closed
          case CircuitBreakerState.Open(since) =>
            val timePassed = (System.currentTimeMillis() - since) > slowCallDurationThreshold.toMillis
            if timePassed then CircuitBreakerState.HalfOpen(System.currentTimeMillis())
            else CircuitBreakerState.Open(since)
          case CircuitBreakerState.HalfOpen(since) =>
            // If halfOpen calls were completed && rates are below we open again
            if halfOpenNumOfCalls.get() == numberOfCallsInHalfOpenState &&
              (failuresRate < failureRateThreshold || slowRate < slowCallThreshold)
            then CircuitBreakerState.Open(System.currentTimeMillis())
            // If halfOpen calls completed, but rates are still above go back to open
            else if halfOpenNumOfCalls.get() == numberOfCallsInHalfOpenState &&
              (failuresRate >= failureRateThreshold || slowRate >= slowCallThreshold)
            then
              halfOpenNumOfCalls.set(0)
              CircuitBreakerState.Open(System.currentTimeMillis())
            // We didn't complete all half open calls, keep halfOpen
            else CircuitBreakerState.HalfOpen(since)

      }
    end nextState

    def getState: CircuitBreakerState = state.get()
  end CircuitBreakerCountStateMachine

  private[resilience] case class CircuitBreakerTimeStateMachine(
      failureRateThreshold: Int,
      slowCallThreshold: Int,
      slowCallDurationThreshold: FiniteDuration,
      windowDuration: FiniteDuration,
      minimumNumberOfCalls: Int,
      numberOfCallsInHalfOpenState: Int,
      waitDurationOpenState: FiniteDuration
  )(using Ox)
      extends CircuitBreakerStateMachine:
    def getState: CircuitBreakerState = ???
    def registerResult(result: CircuitBreakerResult, acquired: AcquireResult): Unit = ???
    def tryAcquire: AcquireResult = ???
  end CircuitBreakerTimeStateMachine
end CircuitBreakerStateMachine
