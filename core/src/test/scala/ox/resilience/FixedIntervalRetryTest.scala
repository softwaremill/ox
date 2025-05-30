package ox.resilience

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.{EitherValues, TryValues}
import ox.util.ElapsedTime
import ox.resilience.*

import scala.concurrent.duration.*
import ox.scheduling.Schedule

class DelayedRetryTest extends AnyFlatSpec with Matchers with EitherValues with TryValues with ElapsedTime:

  behavior of "Delayed retry"

  it should "retry a function" in:
    // given
    val maxRetries = 3
    val sleep = 100.millis
    var counter = 0
    def f =
      counter += 1
      if true then throw new RuntimeException("boom")

    // when
    val (result, elapsedTime) =
      measure(the[RuntimeException] thrownBy retry(Schedule.fixedInterval(sleep).maxRepeats(maxRetries))(f))

    // then
    result should have message "boom"
    elapsedTime.toMillis should be >= maxRetries * sleep.toMillis
    counter shouldBe 4

  it should "retry a failing function forever" in:
    // given
    var counter = 0
    val sleep = 2.millis
    val retriesUntilSuccess = 1_000
    val successfulResult = 42

    def f =
      counter += 1
      if counter <= retriesUntilSuccess then throw new RuntimeException("boom") else successfulResult

    // when
    val result = retry(Schedule.fixedInterval(sleep))(f)

    // then
    result shouldBe successfulResult
    counter shouldBe retriesUntilSuccess + 1

  it should "retry an Either" in:
    // given
    val maxRetries = 3
    val sleep = 100.millis
    var counter = 0
    val errorMessage = "boom"

    def f =
      counter += 1
      Left(errorMessage)

    // when
    val (result, elapsedTime) = measure(retryEither(Schedule.fixedInterval(sleep).maxRepeats(maxRetries))(f))

    // then
    result.left.value shouldBe errorMessage
    elapsedTime.toMillis should be >= maxRetries * sleep.toMillis
    counter shouldBe 4

  behavior of "adaptive retry with delayed config"

  it should "retry a failing function forever or until adaptive retry blocks it" in:
    // given
    var counter = 0
    val sleep = 2.millis
    val retriesUntilSuccess = 1_000
    val successfulResult = 42
    val bucketSize = 500
    val errorMessage = "boom"

    def f =
      counter += 1
      if counter <= retriesUntilSuccess then throw RuntimeException(errorMessage) else successfulResult

    // when
    val adaptive = AdaptiveRetry(TokenBucket(bucketSize), 1, 1)
    val result = the[RuntimeException] thrownBy adaptive.retry(Schedule.fixedInterval(sleep))(f)

    // then
    result should have message errorMessage
    counter shouldBe bucketSize + 1
end DelayedRetryTest
