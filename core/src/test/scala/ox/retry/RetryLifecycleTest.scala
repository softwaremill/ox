package ox.retry

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.{EitherValues, TryValues}
import ox.ElapsedTime
import ox.retry.*

class RetryLifecycleTest extends AnyFlatSpec with Matchers with EitherValues with TryValues with ElapsedTime:
  behavior of "Lifecycle retry"

  it should "retry a succeeding function with lifecycle callbacks" in {
    // given
    var preActionInvoked = false
    var preActionInvocationCount = 0

    var postActionInvoked = false
    var postActionInvocationCount = 0

    var counter = 0
    val successfulResult = 42

    def f =
      counter += 1
      successfulResult

    def preAction(attempt: Int): Unit =
      preActionInvoked = true
      preActionInvocationCount += 1

    var returnedResult: Either[Throwable, Int] = null
    def postAction(attempt: Int, result: Either[Throwable, Int]): Unit =
      postActionInvoked = true
      postActionInvocationCount += 1
      returnedResult = result

    // when
    val result = retry(f)(
      RetryPolicy.immediate(3),
      RetryLifecycle(preAction, postAction)
    )

    // then
    result shouldBe successfulResult
    counter shouldBe 1

    preActionInvoked shouldBe true
    preActionInvocationCount shouldBe 1

    postActionInvoked shouldBe true
    postActionInvocationCount shouldBe 1
    returnedResult shouldBe Right(successfulResult)
  }

  it should "retry a failing function with lifecycle callbacks" in {
    // given
    var preActionInvoked = false
    var preActionInvocationCount = 0

    var postActionInvoked = false
    var postActionInvocationCount = 0

    var counter = 0
    val failedResult = new RuntimeException("boom")

    def f =
      counter += 1
      if true then throw failedResult

    def preAction(attempt: Int): Unit =
      preActionInvoked = true
      preActionInvocationCount += 1

    var returnedResult: Either[Throwable, Unit] = null
    def postAction(attempt: Int, result: Either[Throwable, Unit]): Unit =
      postActionInvoked = true
      postActionInvocationCount += 1
      returnedResult = result

    // when
    val result = the[RuntimeException] thrownBy retry(f)(
      RetryPolicy.immediate(3),
      RetryLifecycle(preAction, postAction)
    )

    // then
    result shouldBe failedResult
    counter shouldBe 4

    preActionInvoked shouldBe true
    preActionInvocationCount shouldBe 4

    postActionInvoked shouldBe true
    postActionInvocationCount shouldBe 4
    returnedResult shouldBe Left(failedResult)
  }
