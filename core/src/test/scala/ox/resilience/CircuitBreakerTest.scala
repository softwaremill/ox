package ox.resilience

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.OptionValues
import scala.concurrent.duration.*
import ox.*
import org.scalatest.EitherValues
import org.scalatest.concurrent.Eventually
import org.scalatest.time.{Millis, Seconds, Span}

class CircuitBreakerTest extends AnyFlatSpec with Matchers with OptionValues with EitherValues with Eventually:
  // Scheduled state transitions are timer-driven, so their observable timing varies with thread scheduling - a single
  // timed read is racy (it caused an intermittent CI failure). The scheduled-state-change tests below poll with
  // `eventually` instead, each with a timeout/interval sized to its own config.

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

  it should "drop operation after exceeding failure threshold" in supervised {
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

  it should "switch to halfOpen after configured time" in supervised {
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

    // then
    result1 shouldBe defined
    // the failing call opens the breaker (immediate)
    eventually(timeout(Span(2, Seconds)), interval(Span(50, Millis))) {
      circuitBreaker.stateMachine.state shouldBe a[CircuitBreakerState.Open]
    }
    // after waitDurationOpenState (1s) it switches to half-open, and stays (halfOpenTimeoutDuration defaults to 0)
    eventually(timeout(Span(5, Seconds)), interval(Span(50, Millis))) {
      circuitBreaker.stateMachine.state shouldBe a[CircuitBreakerState.HalfOpen]
    }
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
        // wide enough that the transient half-open state is reliably sampled by the 50ms poll below
        halfOpenTimeoutDuration = 4.seconds
      )
    )
    def f(): Either[String, String] =
      Left("boom")

    // when
    val result1 = circuitBreaker.runOrDropEither(f()) // trigger switch to open

    // then
    result1 shouldBe defined
    // switches to half-open after 1s; timeout sits inside the 4s half-open window to catch this transient state
    eventually(timeout(Span(4, Seconds)), interval(Span(50, Millis))) {
      circuitBreaker.stateMachine.state shouldBe a[CircuitBreakerState.HalfOpen]
    }
    // no half-open call completes, so it switches back to open; timeout outlasts the 4s half-open window
    eventually(timeout(Span(8, Seconds)), interval(Span(50, Millis))) {
      circuitBreaker.stateMachine.state shouldBe a[CircuitBreakerState.Open]
    }
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
    // Should go back to closed, we have one successful operation
    circuitBreaker.stateMachine.state shouldBe a[CircuitBreakerState.Closed]
  }

end CircuitBreakerTest
