package ox.resilience

import ox.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.{EitherValues, TryValues}
import scala.concurrent.duration.*

class RateLimiterInterfaceTest extends AnyFlatSpec with Matchers with EitherValues with TryValues:
  behavior of "RateLimiter interface"

  it should "drop or block operation depending on method used for fixed rate algorithm" in {
    supervised:
      val rateLimiter = RateLimiter.fixedRate(2, FiniteDuration(1, "second"))

      var executions = 0
      def operation =
        executions += 1
        0

      val result1 = rateLimiter.runOrDrop(operation)
      val result2 = rateLimiter.runOrDrop(operation)
      val result3 = rateLimiter.runOrDrop(operation)
      val result4 = rateLimiter.runBlocking(operation)
      val result5 = rateLimiter.runBlocking(operation)
      val result6 = rateLimiter.runOrDrop(operation)

      result1 shouldBe Some(0)
      result2 shouldBe Some(0)
      result3 shouldBe None
      result4 shouldBe 0
      result5 shouldBe 0
      result6 shouldBe None
      executions shouldBe 4
  }

  it should "drop or block operation depending on method used for sliding window algorithm" in {
    supervised:
      val rateLimiter = RateLimiter.slidingWindow(2, FiniteDuration(1, "second"))

      var executions = 0
      def operation =
        executions += 1
        0

      val result1 = rateLimiter.runOrDrop(operation)
      val result2 = rateLimiter.runOrDrop(operation)
      val result3 = rateLimiter.runOrDrop(operation)
      val result4 = rateLimiter.runBlocking(operation)
      val result5 = rateLimiter.runBlocking(operation)
      val result6 = rateLimiter.runOrDrop(operation)

      result1 shouldBe Some(0)
      result2 shouldBe Some(0)
      result3 shouldBe None
      result4 shouldBe 0
      result5 shouldBe 0
      result6 shouldBe None
      executions shouldBe 4
  }

  it should "drop or block operation depending on method used for bucket algorithm" in {
    supervised:
      val rateLimiter = RateLimiter.bucket(2, FiniteDuration(1, "second"))

      var executions = 0
      def operation =
        executions += 1
        0

      val result1 = rateLimiter.runOrDrop(operation)
      val result2 = rateLimiter.runOrDrop(operation)
      val result3 = rateLimiter.runOrDrop(operation)
      val result4 = rateLimiter.runBlocking(operation)
      val result5 = rateLimiter.runBlocking(operation)
      val result6 = rateLimiter.runOrDrop(operation)

      result1 shouldBe Some(0)
      result2 shouldBe None
      result3 shouldBe None
      result4 shouldBe 0
      result5 shouldBe 0
      result6 shouldBe None
      executions shouldBe 3
  }

  it should "drop or block operation concurrently" in {
    supervised:
      val rateLimiter = RateLimiter.fixedRate(2, FiniteDuration(1, "second"))

      def operation = 0

      var result1: Option[Int] = Some(-1)
      var result2: Option[Int] = Some(-1)
      var result3: Option[Int] = Some(-1)
      var result4: Int = -1
      var result5: Int = -1
      var result6: Int = -1

      // run two operations to block the rate limiter
      rateLimiter.runOrDrop(operation).discard
      rateLimiter.runOrDrop(operation).discard

      // operations with runOrDrop should be dropped while operations with runBlocking should wait
      supervised:
        forkUserDiscard:
          result1 = rateLimiter.runOrDrop(operation)
        forkUserDiscard:
          result2 = rateLimiter.runOrDrop(operation)
        forkUserDiscard:
          result3 = rateLimiter.runOrDrop(operation)
        forkUserDiscard:
          result4 = rateLimiter.runBlocking(operation)
        forkUserDiscard:
          result5 = rateLimiter.runBlocking(operation)
        forkUserDiscard:
          result6 = rateLimiter.runBlocking(operation)

      result1 shouldBe None
      result2 shouldBe None
      result3 shouldBe None
      result4 shouldBe 0
      result5 shouldBe 0
      result6 shouldBe 0
  }
end RateLimiterInterfaceTest
