package ox.resilience

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.{EitherValues, TryValues}
import ox.util.ElapsedTime
import ox.resilience.*
import ox.scheduling.Jitter

import scala.concurrent.duration.*

class BackoffRetryTest extends AnyFlatSpec with Matchers with EitherValues with TryValues with ElapsedTime:

  behavior of "Backoff retry"

  it should "retry a function" in {
    // given
    val maxRetries = 3
    val initialDelay = 100.millis
    var counter = 0
    def f =
      counter += 1
      if true then throw new RuntimeException("boom")

    // when
    val (result, elapsedTime) = measure(the[RuntimeException] thrownBy retry(RetryConfig.backoff(maxRetries, initialDelay))(f))

    // then
    result should have message "boom"
    elapsedTime.toMillis should be >= (initialDelay.toMillis + 2 * initialDelay.toMillis + 4 * initialDelay.toMillis)
    counter shouldBe 4
  }

  it should "retry a failing function forever" in {
    // given
    var counter = 0
    val initialDelay = 100.millis
    val retriesUntilSuccess = 1_000
    val successfulResult = 42

    def f =
      counter += 1
      if counter <= retriesUntilSuccess then throw new RuntimeException("boom") else successfulResult

    // when
    val result = retry(RetryConfig.backoffForever(initialDelay, maxDelay = 2.millis))(f)

    // then
    result shouldBe successfulResult
    counter shouldBe retriesUntilSuccess + 1
  }

  it should "respect maximum delay" in {
    // given
    val maxRetries = 3
    val initialDelay = 100.millis
    val maxDelay = 200.millis
    var counter = 0
    def f =
      counter += 1
      if true then throw new RuntimeException("boom")

    // when
    val (result, elapsedTime) = measure(the[RuntimeException] thrownBy retry(RetryConfig.backoff(maxRetries, initialDelay, maxDelay))(f))

    // then
    result should have message "boom"
    elapsedTime.toMillis should be >= (initialDelay.toMillis + maxDelay.toMillis + maxDelay.toMillis)
    elapsedTime.toMillis should be < initialDelay.toMillis + maxRetries * maxDelay.toMillis
    counter shouldBe 4
  }

  it should "use jitter" in {
    // given
    val maxRetries = 3
    val initialDelay = 100.millis
    val maxDelay = 200.millis
    var counter = 0
    def f =
      counter += 1
      if true then throw new RuntimeException("boom")

    // when
    val (result, elapsedTime) =
      measure(the[RuntimeException] thrownBy retry(RetryConfig.backoff(maxRetries, initialDelay, maxDelay, Jitter.Equal))(f))

    // then
    result should have message "boom"
    elapsedTime.toMillis should be >= (initialDelay.toMillis + maxDelay.toMillis + maxDelay.toMillis) / 2
    elapsedTime.toMillis should be < initialDelay.toMillis + maxRetries * maxDelay.toMillis
    counter shouldBe 4
  }

  it should "retry an Either" in {
    // given
    val maxRetries = 3
    val initialDelay = 100.millis
    var counter = 0
    val errorMessage = "boom"

    def f =
      counter += 1
      Left(errorMessage)

    // when
    val (result, elapsedTime) = measure(retryEither(RetryConfig.backoff(maxRetries, initialDelay))(f))

    // then
    result.left.value shouldBe errorMessage
    elapsedTime.toMillis should be >= (initialDelay.toMillis + 2 * initialDelay.toMillis + 4 * initialDelay.toMillis)
    counter shouldBe 4
  }
end BackoffRetryTest
