package ox.retry

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.{EitherValues, TryValues}
import ox.ElapsedTime
import ox.retry.*

import scala.concurrent.duration.*
import scala.util.Failure

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
    val (result, elapsedTime) = measure(the[RuntimeException] thrownBy retry(f)(RetryPolicy.backoff(maxRetries, initialDelay)))

    // then
    result should have message "boom"
    elapsedTime.toMillis should be >= expectedTotalBackoffTimeMillis(maxRetries, initialDelay)
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
    val result = retry(f)(RetryPolicy.backoffForever(initialDelay, maxDelay = 2.millis))

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
    val (result, elapsedTime) = measure(the[RuntimeException] thrownBy retry(f)(RetryPolicy.backoff(maxRetries, initialDelay, maxDelay)))

    // then
    result should have message "boom"
    elapsedTime.toMillis should be >= expectedTotalBackoffTimeMillis(maxRetries, initialDelay, maxDelay)
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
      measure(the[RuntimeException] thrownBy retry(f)(RetryPolicy.backoff(maxRetries, initialDelay, maxDelay, Jitter.Equal)))

    // then
    result should have message "boom"
    elapsedTime.toMillis should be >= expectedTotalBackoffTimeMillis(maxRetries, initialDelay, maxDelay) / 2
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
    val (result, elapsedTime) = measure(retryEither(f)(RetryPolicy.backoff(maxRetries, initialDelay)))

    // then
    result.left.value shouldBe errorMessage
    elapsedTime.toMillis should be >= expectedTotalBackoffTimeMillis(maxRetries, initialDelay)
    counter shouldBe 4
  }

  it should "retry a Try" in {
    // given
    val maxRetries = 3
    val initialDelay = 100.millis
    var counter = 0
    val errorMessage = "boom"

    def f =
      counter += 1
      Failure(new RuntimeException(errorMessage))

    // when
    val (result, elapsedTime) = measure(retry(f)(RetryPolicy.backoff(maxRetries, initialDelay)))

    // then
    result.failure.exception should have message errorMessage
    elapsedTime.toMillis should be >= expectedTotalBackoffTimeMillis(maxRetries, initialDelay)
    counter shouldBe 4
  }

  private def expectedTotalBackoffTimeMillis(maxRetries: Int, initialDelay: FiniteDuration, maxDelay: FiniteDuration = 1.day): Long =
    (0 until maxRetries).map(Schedule.Backoff.delay(_, initialDelay, maxDelay).toMillis).sum
