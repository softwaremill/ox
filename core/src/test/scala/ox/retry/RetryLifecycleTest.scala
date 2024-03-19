package ox.retry

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.{EitherValues, TryValues}
import ox.retry.*

class RetryLifecycleTest extends AnyFlatSpec with Matchers with EitherValues with TryValues:
  behavior of "Retry lifecycle"

  it should "retry a succeeding function with lifecycle callbacks" in {
    // given
    var beforeEachAttemptInvoked = false
    var beforeEachAttemptInvocationCount = 0

    var afterEachAttemptInvoked = false
    var afterEachAttemptInvocationCount = 0

    var counter = 0
    val successfulResult = 42

    def f =
      counter += 1
      successfulResult

    def beforeEachAttempt(attempt: Int): Unit =
      beforeEachAttemptInvoked = true
      beforeEachAttemptInvocationCount += 1

    var returnedResult: Either[Throwable, Int] = null
    def afterEachAttempt(attempt: Int, result: Either[Throwable, Int]): Unit =
      afterEachAttemptInvoked = true
      afterEachAttemptInvocationCount += 1
      returnedResult = result

    // when
    val result = retry(f)(
      RetryPolicy.immediate(3),
      RetryLifecycle(beforeEachAttempt, afterEachAttempt)
    )

    // then
    result shouldBe successfulResult
    counter shouldBe 1

    beforeEachAttemptInvoked shouldBe true
    beforeEachAttemptInvocationCount shouldBe 1

    afterEachAttemptInvoked shouldBe true
    afterEachAttemptInvocationCount shouldBe 1
    returnedResult shouldBe Right(successfulResult)
  }

  it should "retry a failing function with lifecycle callbacks" in {
    // given
    var beforeEachAttemptInvoked = false
    var beforeEachAttemptInvocationCount = 0

    var afterEachAttemptInvoked = false
    var afterEachAttemptInvocationCount = 0

    var counter = 0
    val failedResult = new RuntimeException("boom")

    def f =
      counter += 1
      if true then throw failedResult

    def beforeEachAttempt(attempt: Int): Unit =
      beforeEachAttemptInvoked = true
      beforeEachAttemptInvocationCount += 1

    var returnedResult: Either[Throwable, Unit] = null
    def afterEachAttempt(attempt: Int, result: Either[Throwable, Unit]): Unit =
      afterEachAttemptInvoked = true
      afterEachAttemptInvocationCount += 1
      returnedResult = result

    // when
    val result = the[RuntimeException] thrownBy retry(f)(
      RetryPolicy.immediate(3),
      RetryLifecycle(beforeEachAttempt, afterEachAttempt)
    )

    // then
    result shouldBe failedResult
    counter shouldBe 4

    beforeEachAttemptInvoked shouldBe true
    beforeEachAttemptInvocationCount shouldBe 4

    afterEachAttemptInvoked shouldBe true
    afterEachAttemptInvocationCount shouldBe 4
    returnedResult shouldBe Left(failedResult)
  }
