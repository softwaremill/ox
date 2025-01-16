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

end CircuitBreakerTest
