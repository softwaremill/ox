package ox.resilience

import scala.concurrent.duration.*
import java.util.concurrent.atomic.AtomicReference
import ox.*

enum CircuitBreakerState:
  case Open
  case Closed
  case HalfOpen

enum CircuitBreakerResult:
  case Success
  case Failure
  case Slow

enum SlidingWindow:
  case CountBased(windowSize: Int)
  case TimeBased(duration: Duration)
  


// TODO -- missing params maxWaitDurationInHalfOpenState
case class CircuitBreakerConfig(
    failureRateThreshold: Int = 50,
    slowCallThreshold: Int = 0,
    slowCallDurationThreshold: Duration = 60.seconds,
    slidingWindow: SlidingWindow = SlidingWindow.CountBased(100),
    minimumNumberOfCalls: Int = 100,
    numberOfCallsInHalfOpenState: Int
)

case class AcquireResult(acquired: Boolean, circuitState: CircuitBreakerState)

trait CircuitBreakerInterface:
  def runOrDrop[T](op: => T): Option[T]
  protected def tryAcquire: Boolean
  def runBlocking[T](op: => T): T
  def state: CircuitBreakerState
  def resultPolicy[E, T]: ResultPolicy[E, T]
  def reset: Unit
end CircuitBreakerInterface

class CircuitBreaker(
    private val config: CircuitBreakerConfig
)(using Ox)
    extends CircuitBreakerInterface:
  val stateMachine: CircuitBreakerStateMachine = CircuitBreakerStateMachine(config)

private sealed trait CircuitBreakerStateMachine:
  def getState: CircuitBreakerState
  def registerResult(result: CircuitBreakerResult): Unit
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
          config.numberOfCallsInHalfOpenState
        )
      case SlidingWindow.TimeBased(duration) =>
        CircuitBreakerTimeStateMachine(
          config.failureRateThreshold,
          config.slowCallThreshold,
          config.slowCallDurationThreshold,
          duration,
          config.minimumNumberOfCalls,
          config.numberOfCallsInHalfOpenState
        )
  end apply

  private[resilience] case class CircuitBreakerCountStateMachine(
      windowSize: Int,
      failureRateThreshold: Int,
      slowCallThreshold: Int,
      slowCallDurationThreshold: Duration,
      minimumNumberOfCalls: Int,
      numberOfCallsInHalfOpenState: Int
  )(using Ox)
      extends CircuitBreakerStateMachine:
    assert(failureRateThreshold >= 0 && failureRateThreshold <=1, s"failureRateThreshold must be between 0 and 100, value: $failureRateThreshold")
    assert(slowCallThreshold >= 0 && slowCallThreshold <=1, s"slowCallThreshold must be between 0 and 100, value: $slowCallThreshold")
    
    private val state: AtomicReference[CircuitBreakerState] = AtomicReference(CircuitBreakerState.Closed)
    private val callResults: AtomicCircularBuffer[CircuitBreakerResult] = AtomicCircularBuffer[CircuitBreakerResult](windowSize)
    private val halfOpenTokens: TokenBucket = TokenBucket(numberOfCallsInHalfOpenState, Some(numberOfCallsInHalfOpenState))

    def registerResult(result: CircuitBreakerResult, acquired: AcquireResult): Unit =
      callResults.push(result)
      state.set(nextState(callResults.snapshot))

    // TODO - should result in info if it was acquired in halfOpen state, so we know if we should release token after operation
    def tryAcquire: AcquireResult = getState match
      case CircuitBreakerState.Open     => AcquireResult(true, CircuitBreakerState.Open)
      case CircuitBreakerState.Closed   => AcquireResult(false, CircuitBreakerState.Closed)
      case CircuitBreakerState.HalfOpen => AcquireResult(halfOpenTokens.tryAcquire(1), CircuitBreakerState.HalfOpen)

    private def nextState(results: Array[CircuitBreakerResult]): CircuitBreakerState =
      val numOfOperations = results.size
      val failuresRate = ((results.count(_ == CircuitBreakerResult.Failure) / windowSize.toFloat) * 100).toInt
      val slowRate = ((results.count(_ == CircuitBreakerResult.Slow) / windowSize.toFloat) * 100).toInt
      state.updateAndGet { current =>
        current match
          case CircuitBreakerState.Open =>
            if numOfOperations >= minimumNumberOfCalls && (failuresRate < failureRateThreshold || slowRate < slowCallThreshold) then
              // TODO - start time to go to HalfOpen
              CircuitBreakerState.Closed
            else CircuitBreakerState.Open
          case CircuitBreakerState.Closed   =>
          case CircuitBreakerState.HalfOpen =>

      }
    end nextState

    def getState: CircuitBreakerState = state.get()
  end CircuitBreakerCountStateMachine

  private[resilience] case class CircuitBreakerTimeStateMachine(
      failureRateThreshold: Int,
      slowCallThreshold: Int,
      slowCallDurationThreshold: Duration,
      windowDuration: Duration,
      minimumNumberOfCalls: Int,
      numberOfCallsInHalfOpenState: Int
  )(using Ox)
      extends CircuitBreakerStateMachine
end CircuitBreakerStateMachine
