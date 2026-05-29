package ox.resilience

import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.scheduling.Schedule

class AdaptiveRetryTest extends AnyFlatSpec with EitherValues with Matchers:

  behavior of "AdaptiveRetry"

  it should "not pay failureCost if result E is going to be retried and shouldPayFailureCost returns false" in:
    // given
    var counter = 0
    val errorMessage = "boom"

    def f: Either[String, Int] =
      counter += 1
      Left(errorMessage)

    // Bucket smaller than the number of attempts: if the cost were paid, we'd stop early.
    val adaptive = AdaptiveRetry(TokenBucket(2), 1, 1)

    // when
    val result = adaptive.retryEither(Schedule.immediate.maxRetries(5), _ => false)(f)

    // then
    result.left.value shouldBe errorMessage
    // 1 initial + 5 retries, all for free since shouldPayFailureCost returns false
    counter shouldBe 6

end AdaptiveRetryTest
