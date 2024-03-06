package ox.retry

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.{EitherValues, TryValues}
import ox.ElapsedTime
import ox.retry.*

import scala.concurrent.duration.*
import scala.util.Failure

class DelayedRetryTest extends AnyFlatSpec with Matchers with EitherValues with TryValues with ElapsedTime:

  behavior of "Delayed retry"

  it should "retry a function" in {
    // given
    val maxRetries = 3
    val sleep = 100.millis
    var counter = 0
    def f =
      counter += 1
      if true then throw new RuntimeException("boom")

    // when
    val (result, elapsedTime) = measure(the[RuntimeException] thrownBy retry(f)(RetryPolicy.delay(maxRetries, sleep)))

    // then
    result should have message "boom"
    elapsedTime.toMillis should be >= maxRetries * sleep.toMillis
    counter shouldBe 4
  }

  it should "retry a failing function forever" in {
    // given
    var counter = 0
    val sleep = 2.millis
    val retriesUntilSuccess = 1_000
    val successfulResult = 42

    def f =
      counter += 1
      if counter <= retriesUntilSuccess then throw new RuntimeException("boom") else successfulResult

    // when
    val result = retry(f)(RetryPolicy.delayForever(sleep))

    // then
    result shouldBe successfulResult
    counter shouldBe retriesUntilSuccess + 1
  }

  it should "retry an Either" in {
    // given
    val maxRetries = 3
    val sleep = 100.millis
    var counter = 0
    val errorMessage = "boom"

    def f =
      counter += 1
      Left(errorMessage)

    // when
    val (result, elapsedTime) = measure(retryEither(f)(RetryPolicy.delay(maxRetries, sleep)))

    // then
    result.left.value shouldBe errorMessage
    elapsedTime.toMillis should be >= maxRetries * sleep.toMillis
    counter shouldBe 4
  }
