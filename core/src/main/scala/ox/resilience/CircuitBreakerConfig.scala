package ox.resilience

import scala.concurrent.duration.*
import java.util.concurrent.TimeUnit

/** Allows to configure how [[Metrics]] will be calculated
  */
enum SlidingWindow:
  /** Window counting last n operations when calculating metrics.
    * @param windowSize
    *   number of last n results recored.
    */
  case CountBased(windowSize: Int)

  /** Window counting operations in the lapse of `duraiton` before current time.
    * @param duration
    *   span of time where results are considered for including in metrics.
    */
  case TimeBased(duration: FiniteDuration)
end SlidingWindow

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
    failureRateThreshold: Int = 50,
    slowCallThreshold: Int = 0,
    slowCallDurationThreshold: FiniteDuration = 60.seconds,
    slidingWindow: SlidingWindow = SlidingWindow.CountBased(100),
    minimumNumberOfCalls: Int = 20,
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
  assert(
    numberOfCallsInHalfOpenState > 0,
    s"numberOfCallsInHalfOpenState must be greater than 0, value: $numberOfCallsInHalfOpenState"
  )
end CircuitBreakerConfig
