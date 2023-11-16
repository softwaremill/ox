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
    val (result, elapsedTime) = measure(the[RuntimeException] thrownBy retry(f)(RetryPolicy.Delay(maxRetries, sleep)))

    // then
    result should have message "boom"
    elapsedTime.toMillis should be >= maxRetries * sleep.toMillis
    counter shouldBe 4
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
    val (result, elapsedTime) = measure(retry(f)(RetryPolicy.Delay(maxRetries, sleep)))

    // then
    result.left.value shouldBe errorMessage
    elapsedTime.toMillis should be >= maxRetries * sleep.toMillis
    counter shouldBe 4
  }

  it should "retry a Try" in {
    // given
    val maxRetries = 3
    val sleep = 100.millis
    var counter = 0
    val errorMessage = "boom"

    def f =
      counter += 1
      Failure(new RuntimeException(errorMessage))

    // when
    val (result, elapsedTime) = measure(retry(f)(RetryPolicy.Delay(maxRetries, sleep)))

    // then
    result.failure.exception should have message errorMessage
    elapsedTime.toMillis should be >= maxRetries * sleep.toMillis
    counter shouldBe 4
  }
