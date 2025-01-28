package ox.resilience

import scala.concurrent.duration.*

/** Allows to configure how [[Metrics]] will be calculated
  */
enum SlidingWindow:
  /** Window counting last n operations when calculating metrics.
    * @param windowSize
    *   number of last n results recorded.
    */
  case CountBased(windowSize: Int)

  /** Window counting operations in the lapse of `duration` before current time.
    * @param duration
    *   span of time in which results are included in metrics.
    */
  case TimeBased(duration: FiniteDuration)
end SlidingWindow

/** Type representing percentage threshold between 0 and 100 */
opaque type PercentageThreshold = Int

extension (c: PercentageThreshold)
  def toInt: Int = c
  def isExceeded(by: Int): Boolean = by >= c

object PercentageThreshold:
  def apply(c: Int): PercentageThreshold =
    assert(c >= 0 && c <= 100, s"PercentageThreshold must be between 0 and 100, value: $c")
    c

/** @param failureRateThreshold
  *   threshold, as percentage of operations that ended in failure
  * @param slowCallThreshold
  *   threshold, as percentage of operations that spanned more then [[slowCallDurationThreshold]].
  * @param slowCallDurationThreshold
  *   time after which operation is considered slow.
  * @param slidingWindow
  *   configures how thresholds will be calculated. See [[SlidingWindow]] for more details.
  * @param minimumNumberOfCalls
  *   minimum number of results that must be registered before metrics are calculated.
  * @param waitDurationOpenState
  *   how much time will pass before breaker will switch from open to half open state.
  * @param halfOpenTimeoutDuration
  *   time out after which, if not enough calls where registered in half open state, breaker will go back to open state.
  * @param numberOfCallsInHalfOpenState
  *   number of results that must be registered to calculate metrics and decide if breaker should go back to open state or close. This is
  *   also maximum number of operations that can be started in half open state.
  */
case class CircuitBreakerConfig(
    failureRateThreshold: PercentageThreshold,
    slowCallThreshold: PercentageThreshold,
    slowCallDurationThreshold: FiniteDuration,
    slidingWindow: SlidingWindow,
    minimumNumberOfCalls: Int,
    waitDurationOpenState: FiniteDuration,
    halfOpenTimeoutDuration: FiniteDuration,
    numberOfCallsInHalfOpenState: Int
):

  assert(
    numberOfCallsInHalfOpenState > 0,
    s"numberOfCallsInHalfOpenState must be greater than 0, value: $numberOfCallsInHalfOpenState"
  )
end CircuitBreakerConfig

object CircuitBreakerConfig:
  def default: CircuitBreakerConfig = CircuitBreakerConfig(
    failureRateThreshold = PercentageThreshold(50),
    slowCallThreshold = PercentageThreshold(50),
    slowCallDurationThreshold = 10.seconds,
    slidingWindow = SlidingWindow.CountBased(100),
    minimumNumberOfCalls = 20,
    waitDurationOpenState = 10.seconds,
    halfOpenTimeoutDuration = 0.millis,
    numberOfCallsInHalfOpenState = 10
  )
end CircuitBreakerConfig
