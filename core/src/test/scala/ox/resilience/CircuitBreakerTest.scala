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
    val thresholdRate = PercentageThreshold(100)
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
    val thresholdRate = PercentageThreshold(100)
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
    val thresholdRate = PercentageThreshold(100)
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
    val thresholdRate = PercentageThreshold(100)
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
    val thresholdRate = PercentageThreshold(100)
    val numberOfOperations = 1
    val circuitBreaker = CircuitBreaker(
      CircuitBreakerConfig.default.copy(
        failureRateThreshold = thresholdRate,
        minimumNumberOfCalls = numberOfOperations,
        slidingWindow = SlidingWindow.CountBased(numberOfOperations),
        numberOfCallsInHalfOpenState = 1,
        waitDurationOpenState = 1.seconds,
        halfOpenTimeoutDuration = 2.seconds
      )
    )
    def f(): Either[String, String] =
      Left("boom")

    // when
    val result1 = circuitBreaker.runOrDropEither(f()) // trigger switch to open
    sleep(1500.millis) // wait for state to register, and for switch to half open
    val state = circuitBreaker.stateMachine.state
    sleep(2500.millis) // wait longer than half open timeout
    val stateAfterWait = circuitBreaker.stateMachine.state

    // then
    result1 shouldBe defined
    state shouldBe a[CircuitBreakerState.HalfOpen]
    stateAfterWait shouldBe a[CircuitBreakerState.Open]
  }

  it should "correctly transitions through states when there are concurrently running operations" in supervised {
    // given
    val circuitBreaker = CircuitBreaker(
      CircuitBreakerConfig.default.copy(
        failureRateThreshold = PercentageThreshold(100),
        minimumNumberOfCalls = 1,
        slidingWindow = SlidingWindow.TimeBased(2.seconds),
        numberOfCallsInHalfOpenState = 1,
        waitDurationOpenState = 1.second,
        halfOpenTimeoutDuration = 1.second
      )
    )

    // when

    // concurrently, run two failing operations
    forkDiscard {
      circuitBreaker.runOrDropEither {
        sleep(500.millis)
        Left("a")
      }
    }
    forkDiscard {
      circuitBreaker.runOrDropEither {
        sleep(1.second)
        Left("b")
      }
    }

    // then

    // 250ms: no operations complete yet, should be closed
    sleep(250.millis)
    circuitBreaker.stateMachine.state shouldBe a[CircuitBreakerState.Closed]

    // 750ms: the first operation failed, should be open
    sleep(500.millis)
    circuitBreaker.stateMachine.state shouldBe a[CircuitBreakerState.Open]

    // 1750ms: first operation failed more than 1s ago, second operation failed less than 1s ago and was ignored
    sleep(1.second)
    circuitBreaker.stateMachine.state shouldBe a[CircuitBreakerState.HalfOpen]

    // 2250ms: more than 1s after the last failing operation, should be now half-open
    sleep(500.millis)
    circuitBreaker.stateMachine.state shouldBe a[CircuitBreakerState.HalfOpen]

    // 3250ms: at 2500ms 1 sec timeout on halfOpen state passes, we go back to open
    sleep(1.second)
    circuitBreaker.stateMachine.state shouldBe a[CircuitBreakerState.Open]

    // 3750ms: at 3500ms we go to halfOpen again
    sleep(1000.millis)
    circuitBreaker.stateMachine.state shouldBe a[CircuitBreakerState.HalfOpen]
  }

  it should "correctly calculate metrics when results come in after state change" in supervised {
    // given
    val circuitBreaker = CircuitBreaker(
      CircuitBreakerConfig.default.copy(
        failureRateThreshold = PercentageThreshold(50),
        minimumNumberOfCalls = 1,
        slidingWindow = SlidingWindow.TimeBased(4.seconds),
        numberOfCallsInHalfOpenState = 1,
        waitDurationOpenState = 1.second,
        halfOpenTimeoutDuration = 1.second
      )
    )

    // when

    // concurrently, run two failing operations
    forkDiscard {
      circuitBreaker.runOrDropEither {
        sleep(500.millis)
        Left("a")
      }
    }
    forkDiscard {
      circuitBreaker.runOrDropEither {
        sleep(2.second)
        Left("b")
      }
    }

    // then

    // 250ms: no operations complete yet, should be closed
    sleep(250.millis)
    circuitBreaker.stateMachine.state shouldBe a[CircuitBreakerState.Closed]

    // 750ms: the first operation failed, should be open
    sleep(500.millis)
    circuitBreaker.stateMachine.state shouldBe a[CircuitBreakerState.Open]

    // 1750ms: first operation failed more than 1s ago, second operation failed less than 1s ago and was ignored
    sleep(1.second)
    circuitBreaker.stateMachine.state shouldBe a[CircuitBreakerState.HalfOpen]

    // 2250ms: complete enough operations for halfOpen state - since success should switch back to Closed
    sleep(500.millis)
    circuitBreaker.runOrDropEither(Right("c")).discard
    sleep(100.millis) // wait for state to register
    // Should go back to closed, we have one succesful operation
    circuitBreaker.stateMachine.state shouldBe a[CircuitBreakerState.Closed]
  }

end CircuitBreakerTest
