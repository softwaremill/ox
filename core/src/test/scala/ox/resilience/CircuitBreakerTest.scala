package ox.resilience

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.OptionValues
import scala.concurrent.duration.*
import ox.*
import org.scalatest.EitherValues

class CircuitBreakerTest extends AnyFlatSpec with Matchers with OptionValues with EitherValues:
  behavior of "Circuit Breaker run operations"

  it should "run operation when metrics are not exceeded" in supervised {
    // given
    val thresholdRate = 100
    val numberOfOperations = 2
    val circuitBreaker = CircuitBreaker(
      CircuitBreakerConfig.default.copy(
        failureRateThreshold = thresholdRate,
        minimumNumberOfCalls = numberOfOperations,
        slidingWindow = SlidingWindow.CountBased(numberOfOperations),
        numberOfCallsInHalfOpenState = 1
      )
    )
    var counter = 0
    def f(): Either[String, String] =
      counter += 1
      if counter <= 1 then Left("boom")
      else Right("success")

    // when
    val result1 = circuitBreaker.runOrDropEither(f())
    sleep(100.millis) // wait for state to register
    val result2 = circuitBreaker.runOrDropEither(f())

    // then
    result1 shouldBe defined
    result1.value.left.value shouldBe "boom"
    result2 shouldBe defined
    result2.value.value shouldBe "success"
  }

  it should "drop operation after exceeding fauilure threshold" in supervised {
    // given
    val thresholdRate = 100
    val numberOfOperations = 1
    val circuitBreaker = CircuitBreaker(
      CircuitBreakerConfig.default.copy(
        failureRateThreshold = thresholdRate,
        minimumNumberOfCalls = numberOfOperations,
        slidingWindow = SlidingWindow.CountBased(numberOfOperations),
        numberOfCallsInHalfOpenState = 1
      )
    )
    def f(): Either[String, String] =
      Left("boom")

    // when
    val result1 = circuitBreaker.runOrDropEither(f())
    sleep(100.millis) // wait for state to register
    val result2 = circuitBreaker.runOrDropEither(f())

    // then
    result1 shouldBe defined
    result2 shouldBe empty
  }

  it should "drop operation after exceeding slow call threshold" in supervised {
    // given
    val thresholdRate = 100
    val numberOfOperations = 1
    val circuitBreaker = CircuitBreaker(
      CircuitBreakerConfig.default.copy(
        slowCallThreshold = thresholdRate,
        minimumNumberOfCalls = numberOfOperations,
        slowCallDurationThreshold = 100.millis,
        slidingWindow = SlidingWindow.CountBased(numberOfOperations),
        numberOfCallsInHalfOpenState = 1
      )
    )
    def f(): Either[String, String] =
      sleep(500.millis)
      Right("success")

    // when
    val result1 = circuitBreaker.runOrDropEither(f())
    sleep(100.millis) // wait for state to register
    val result2 = circuitBreaker.runOrDropEither(f())

    // then
    result1 shouldBe defined
    result2 shouldBe empty
  }

  behavior of "Circuit Breaker scheduled state changes"

  it should "switch to halfopen after configured time" in supervised {
    // given
    val thresholdRate = 100
    val numberOfOperations = 1
    val circuitBreaker = CircuitBreaker(
      CircuitBreakerConfig.default.copy(
        failureRateThreshold = thresholdRate,
        minimumNumberOfCalls = numberOfOperations,
        slidingWindow = SlidingWindow.CountBased(numberOfOperations),
        numberOfCallsInHalfOpenState = 1,
        waitDurationOpenState = 1.second
      )
    )
    def f(): Either[String, String] =
      Left("boom")

    // when
    val result1 = circuitBreaker.runOrDropEither(f())
    sleep(100.millis) // wait for state to register
    val state = circuitBreaker.stateMachine.state
    sleep(1500.millis)
    val stateAfterWait = circuitBreaker.stateMachine.state

    // then
    result1 shouldBe defined
    state shouldBe a[CircuitBreakerState.Open]
    stateAfterWait shouldBe a[CircuitBreakerState.HalfOpen]
  }

  it should "switch back to open after configured timeout in half open state" in supervised {
    // given
    val thresholdRate = 100
    val numberOfOperations = 1
    val circuitBreaker = CircuitBreaker(
      CircuitBreakerConfig.default.copy(
        failureRateThreshold = thresholdRate,
        minimumNumberOfCalls = numberOfOperations,
        slidingWindow = SlidingWindow.CountBased(numberOfOperations),
        numberOfCallsInHalfOpenState = 1,
        waitDurationOpenState = 10.millis,
        halfOpenTimeoutDuration = 1.second
      )
    )
    def f(): Either[String, String] =
      Left("boom")

    // when
    val result1 = circuitBreaker.runOrDropEither(f()) // trigger swithc to open
    sleep(100.millis) // wait for state to register, and for switch to half open
    val state = circuitBreaker.stateMachine.state
    sleep(1500.millis) // wait longer than half open timeout
    val stateAfterWait = circuitBreaker.stateMachine.state

    // then
    result1 shouldBe defined
    state shouldBe a[CircuitBreakerState.HalfOpen]
    stateAfterWait shouldBe a[CircuitBreakerState.Open]
  }

end CircuitBreakerTest
