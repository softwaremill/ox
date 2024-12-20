package ox.resilience

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.{EitherValues, TryValues}
import ox.resilience.*
import ox.scheduling.Schedule

class OnRetryTest extends AnyFlatSpec with Matchers with EitherValues with TryValues:
  behavior of "RetryPolicy onRetry callback"

  it should "retry a succeeding function with onRetry callback" in {
    // given
    var onRetryInvocationCount = 0

    var counter = 0
    val successfulResult = 42

    def f =
      counter += 1
      successfulResult

    var returnedResult: Either[Throwable, Int] = null
    def onRetry(attempt: Int, result: Either[Throwable, Int]): Unit =
      onRetryInvocationCount += 1
      returnedResult = result

    // when
    val result = retry(RetryConfig(Schedule.Immediate(3), onRetry = onRetry))(f)

    // then
    result shouldBe successfulResult
    counter shouldBe 1

    onRetryInvocationCount shouldBe 1
    returnedResult shouldBe Right(successfulResult)
  }

  it should "retry a failing function with onRetry callback" in {
    // given
    var onRetryInvocationCount = 0

    var counter = 0
    val failedResult = new RuntimeException("boom")

    def f =
      counter += 1
      if true then throw failedResult

    var returnedResult: Either[Throwable, Unit] = null
    def onRetry(attempt: Int, result: Either[Throwable, Unit]): Unit =
      onRetryInvocationCount += 1
      returnedResult = result

    // when
    val result = the[RuntimeException] thrownBy retry(RetryConfig(Schedule.Immediate(3), onRetry = onRetry))(f)

    // then
    result shouldBe failedResult
    counter shouldBe 4

    onRetryInvocationCount shouldBe 4
    returnedResult shouldBe Left(failedResult)
  }
end OnRetryTest
