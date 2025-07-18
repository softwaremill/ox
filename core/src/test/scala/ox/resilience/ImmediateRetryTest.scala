package ox.resilience

import org.scalatest.{EitherValues, TryValues}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.resilience.*
import ox.scheduling.Schedule

class ImmediateRetryTest extends AnyFlatSpec with EitherValues with TryValues with Matchers:

  behavior of "Immediate retry"

  it should "retry a succeeding function" in:
    // given
    var counter = 0
    val successfulResult = 42

    def f =
      counter += 1
      successfulResult

    // when
    val result = retry(Schedule.immediate.maxRetries(3))(f)

    // then
    result shouldBe successfulResult
    counter shouldBe 1

  it should "fail fast when a function is not worth retrying" in:
    // given
    var counter = 0
    val errorMessage = "boom"
    val policy = RetryConfig[Throwable, Unit](Schedule.immediate.maxRetries(3), ResultPolicy.retryWhen(_.getMessage != errorMessage))

    def f =
      counter += 1
      if true then throw new RuntimeException(errorMessage)

    // when/then
    the[RuntimeException] thrownBy retry(policy)(f) should have message errorMessage
    counter shouldBe 1

  it should "retry a succeeding function with a custom success condition" in:
    // given
    var counter = 0
    val unsuccessfulResult = -1
    val policy = RetryConfig[Throwable, Int](Schedule.immediate.maxRetries(3), ResultPolicy.successfulWhen(_ > 0))

    def f =
      counter += 1
      unsuccessfulResult

    // when
    val result = retry(policy)(f)

    // then
    result shouldBe unsuccessfulResult
    counter shouldBe 4

  it should "retry a failing function" in:
    // given
    var counter = 0
    val errorMessage = "boom"

    def f =
      counter += 1
      if true then throw new RuntimeException(errorMessage)

    // when/then
    the[RuntimeException] thrownBy retry(Schedule.immediate.maxRetries(3))(f) should have message errorMessage
    counter shouldBe 4

  it should "retry a failing function forever" in:
    // given
    var counter = 0
    val retriesUntilSuccess = 10_000
    val successfulResult = 42

    def f =
      counter += 1
      if counter <= retriesUntilSuccess then throw new RuntimeException("boom") else successfulResult

    // when
    val result = retry(Schedule.immediate)(f)

    // then
    result shouldBe successfulResult
    counter shouldBe retriesUntilSuccess + 1

  it should "retry a succeeding Either" in:
    // given
    var counter = 0
    val successfulResult = 42

    def f: Either[String, Int] =
      counter += 1
      Right(successfulResult)

    // when
    val result = retryEither(Schedule.immediate.maxRetries(3))(f)

    // then
    result.value shouldBe successfulResult
    counter shouldBe 1

  it should "fail fast when an Either is not worth retrying" in:
    // given
    var counter = 0
    val errorMessage = "boom"
    val policy: RetryConfig[String, Int] = RetryConfig(Schedule.immediate.maxRetries(3), ResultPolicy.retryWhen(_ != errorMessage))

    def f: Either[String, Int] =
      counter += 1
      Left(errorMessage)

    // when
    val result = retryEither(policy)(f)

    // then
    result.left.value shouldBe errorMessage
    counter shouldBe 1

  it should "retry a succeeding Either with a custom success condition" in:
    // given
    var counter = 0
    val unsuccessfulResult = -1
    val policy: RetryConfig[String, Int] = RetryConfig(Schedule.immediate.maxRetries(3), ResultPolicy.successfulWhen(_ > 0))

    def f: Either[String, Int] =
      counter += 1
      Right(unsuccessfulResult)

    // when
    val result = retryEither(policy)(f)

    // then
    result.value shouldBe unsuccessfulResult
    counter shouldBe 4

  it should "retry a failing Either" in:
    // given
    var counter = 0
    val errorMessage = "boom"

    def f =
      counter += 1
      Left(errorMessage)

    // when
    val result = retryEither(Schedule.immediate.maxRetries(3))(f)

    // then
    result.left.value shouldBe errorMessage
    counter shouldBe 4

  behavior of "Adaptive retry with immediate config"

  it should "retry a failing adaptive" in:
    // given
    var counter = 0
    val errorMessage = "boom"

    def f =
      counter += 1
      if counter <= 2 then Left(errorMessage)
      else Right("Success")

    val adaptive = AdaptiveRetry(TokenBucket(5), 1, 1)
    // when
    val result = adaptive.retryEither(Schedule.immediate.maxRetries(5))(f)

    // then
    result.value shouldBe "Success"
    counter shouldBe 3

  it should "stop retrying after emptying bucket" in:
    // given
    var counter = 0
    val errorMessage = "boom"

    def f =
      counter += 1
      Left(errorMessage)

    val adaptive = AdaptiveRetry(TokenBucket(2), 1, 1)
    // when
    val result = adaptive.retryEither(Schedule.immediate.maxRetries(5))(f)

    // then
    result.left.value shouldBe errorMessage
    // One for first try, two for retries with bucket size 2
    counter shouldBe 3

  it should "not pay exceptionCost if result T is going to be retried and shouldPayPenaltyCost returns false" in:
    // given
    var counter = 0
    val message = "success"

    def f =
      counter += 1
      Right(message)

    val adaptive = AdaptiveRetry(TokenBucket(2), 1, 1)
    val retryConfig =
      RetryConfig(Schedule.immediate.maxRetries(5)).copy(resultPolicy = ResultPolicy.successfulWhen[String, String](_ => false))
    // when
    val result = adaptive.retryEither(retryConfig, _ => false)(f)

    // then
    result.value shouldBe message
    counter shouldBe 6

end ImmediateRetryTest
