package ox.resilience

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.duration.*
import ox.*

class CircuitBreakerTest extends AnyFlatSpec with Matchers:
  behavior of "Circuit Breaker"

  it should "drop operation after exceeding threshold" in supervised {
    // given
    val thresholdRate = 100
    val numberOfOperations = 1
    val circuitBreaker = CircuitBreaker(
      CircuitBreakerConfig(
        failureRateThreshold = thresholdRate,
        minimumNumberOfCalls = numberOfOperations,
        slidingWindow = SlidingWindow.CountBased(numberOfOperations),
        numberOfCallsInHalfOpenState = 0
      )
    )

    def f(): Either[String, String] =
      Left("boom")
    // when
    val result1 = circuitBreaker.runOrDropEither(ResultPolicy.default)(f())
    sleep(100.millis) // wait for state to register
    val result2 = circuitBreaker.runOrDropEither(ResultPolicy.default)(f())

    // then
    result1 shouldBe defined
    result2 shouldBe empty
  }

  it should "run" in supervised {
    // given
    val thresholdRate = 100
    val numberOfOperations = 10
    val circuitBreaker = CircuitBreaker(
      CircuitBreakerConfig(
        failureRateThreshold = thresholdRate,
        minimumNumberOfCalls = numberOfOperations,
        slidingWindow = SlidingWindow.CountBased(numberOfOperations)
      )
    )
    var counter = 0
    def f(): Either[String, String] =
      sleep(100.millis)
      counter += 1
      Left("boom")

    // when
    0 to 50 foreach: _ =>
      circuitBreaker.runOrDropEither(ResultPolicy.default)(f())

    // then
    println(counter)
  }

end CircuitBreakerTest
