package ox.resilience

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.{EitherValues, TryValues}
import ox.resilience.*
import ox.scheduling.Schedule

class AfterAttemptTest extends AnyFlatSpec with Matchers with EitherValues with TryValues:
  behavior of "RetryPolicy afterAttempt callback"

  it should "retry a succeeding function with afterAttempt callback" in:
    // given
    var afterAttemptInvocationCount = 0

    var counter = 0
    val successfulResult = 42

    def f =
      counter += 1
      successfulResult

    var returnedResult: Either[Throwable, Int] = null
    def afterAttempt(attempt: Int, result: Either[Throwable, Int]): Unit =
      afterAttemptInvocationCount += 1
      returnedResult = result

    // when
    val result = retry(RetryConfig(Schedule.immediate.maxAttempts(4), afterAttempt = afterAttempt))(f)

    // then
    result shouldBe successfulResult
    counter shouldBe 1

    afterAttemptInvocationCount shouldBe 1
    returnedResult shouldBe Right(successfulResult)

  it should "retry a failing function with afterAttempt callback" in:
    // given
    var afterAttemptInvocationCount = 0

    var counter = 0
    val failedResult = new RuntimeException("boom")

    def f =
      counter += 1
      if true then throw failedResult

    var returnedResult: Either[Throwable, Unit] = null
    def afterAttempt(attempt: Int, result: Either[Throwable, Unit]): Unit =
      afterAttemptInvocationCount += 1
      returnedResult = result

    // when
    val result = the[RuntimeException] thrownBy retry(RetryConfig(Schedule.immediate.maxAttempts(4), afterAttempt = afterAttempt))(f)

    // then
    result shouldBe failedResult
    counter shouldBe 4

    afterAttemptInvocationCount shouldBe 4
    returnedResult shouldBe Left(failedResult)
end AfterAttemptTest
