package ox.resilience

import ox.*
import ox.util.ElapsedTime
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.util.concurrent.atomic.AtomicLong
import org.scalatest.{EitherValues, TryValues}
import scala.concurrent.duration.*
import java.util.concurrent.atomic.AtomicReference
import ox.resilience.RateLimiterMode

class RateLimiterTest extends AnyFlatSpec with Matchers with EitherValues with TryValues with ElapsedTime:
  behavior of "fixed rate RateLimiter"

  it should "drop operation when rate limit is exceeded" in {
    supervised:
      val rateLimiter = RateLimiter(
        RateLimiterAlgorithm.FixedWindow(2, FiniteDuration(1, "second"))
      )

      var executions = 0
      def operation =
        executions += 1
        0

      val result1 = rateLimiter.runOrDrop(operation)
      val result2 = rateLimiter.runOrDrop(operation)
      val result3 = rateLimiter.runOrDrop(operation)

      result1 shouldBe Some(0)
      result2 shouldBe Some(0)
      result3 shouldBe None
      executions shouldBe 2
  }

  it should "restart rate limiter after given duration" in {
    supervised:
      val rateLimiter = RateLimiter(
        RateLimiterAlgorithm.FixedWindow(2, FiniteDuration(1, "second"))
      )

      var executions = 0
      def operation =
        executions += 1
        0

      val result1 = rateLimiter.runOrDrop(operation)
      val result2 = rateLimiter.runOrDrop(operation)
      val result3 = rateLimiter.runOrDrop(operation)
      ox.sleep(1.second)
      ox.sleep(100.milliseconds) // make sure the rate limiter is replenished
      val result4 = rateLimiter.runOrDrop(operation)

      result1 shouldBe Some(0)
      result2 shouldBe Some(0)
      result3 shouldBe None
      result4 shouldBe Some(0)
      executions shouldBe 3
  }

  it should "block operation when rate limit is exceeded" in {
    supervised {
      val rateLimiter = RateLimiter(
        RateLimiterAlgorithm.FixedWindow(2, FiniteDuration(1, "second"))
      )

      var executions = 0
      def operation =
        executions += 1
        0

      val before = System.currentTimeMillis()
      val result1 = rateLimiter.runBlocking(operation)
      val result2 = rateLimiter.runBlocking(operation)
      val result3 = rateLimiter.runBlocking(operation)
      val after = System.currentTimeMillis()

      result1 shouldBe 0
      result2 shouldBe 0
      result3 shouldBe 0
      (after - before) should be >= 1000L
      executions shouldBe 3
    }
  }

  it should "respect time constraints when blocking" in {
    supervised {
      val rateLimiter = RateLimiter(
        RateLimiterAlgorithm.FixedWindow(2, FiniteDuration(1, "second"))
      )

      var order = List.empty[Int]
      def operationN(n: Int) =
        rateLimiter.runBlocking {
          order = n :: order
          n
        }

      val time1 = System.currentTimeMillis() // 0 seconds
      val result1 = operationN(1)
      val result2 = operationN(2)
      val result3 = operationN(3) // blocks until 1 second elapsed
      val time2 = System.currentTimeMillis() // 1 second
      val result4 = operationN(4)
      val result5 = operationN(5) // blocks until 2 seconds elapsed
      val time3 = System.currentTimeMillis()
      val result6 = operationN(6)
      val result7 = operationN(7) // blocks until 3 seconds elapsed
      val time4 = System.currentTimeMillis()
      val result8 = operationN(8)
      val result9 = operationN(9) // blocks until 4 seconds elapsed
      val time5 = System.currentTimeMillis()

      result1 shouldBe 1
      result2 shouldBe 2
      result3 shouldBe 3
      result4 shouldBe 4
      result5 shouldBe 5
      result6 shouldBe 6
      result7 shouldBe 7
      result8 shouldBe 8
      result9 shouldBe 9
      (time2 - time1) should be >= 1000L - 10
      (time3 - time1) should be >= 2000L - 10
      (time4 - time1) should be >= 3000L - 10
      (time5 - time1) should be >= 4000L - 10
      (time2 - time1) should be <= 1200L
      (time3 - time1) should be <= 2200L
      (time4 - time1) should be <= 3200L
      (time5 - time1) should be <= 4200L
      order should be(List(9, 8, 7, 6, 5, 4, 3, 2, 1))
    }
  }

  it should "respect time constraints when blocking concurrently" in {
    supervised {
      val rateLimiter = RateLimiter(
        RateLimiterAlgorithm.FixedWindow(2, FiniteDuration(1, "second"))
      )

      val order = new AtomicReference(List.empty[Int])
      def operationN(n: Int) =
        rateLimiter.runBlocking {
          order.updateAndGet(ord => n :: ord)
          n
        }

      val before = System.currentTimeMillis() // 0 seconds
      supervised {
        forkUserDiscard:
          operationN(1)
        forkUserDiscard:
          sleep(50.millis)
          operationN(2)
        forkUserDiscard:
          sleep(100.millis)
          operationN(3)
        forkUserDiscard:
          sleep(150.millis)
          operationN(4)
        forkUserDiscard:
          sleep(200.millis)
          operationN(5)
        forkUserDiscard:
          sleep(250.millis)
          operationN(6)
        forkUserDiscard:
          sleep(300.millis)
          operationN(7)
        forkUserDiscard:
          sleep(350.millis)
          operationN(8)
        forkUserDiscard:
          sleep(400.millis)
          operationN(9)
      }
      val after = System.currentTimeMillis()

      (after - before) should be >= 4000L - 10
      (after - before) should be <= 4200L
    }
  }

  it should "allow to run more long running operations concurrently than max rate when not considering operation's time" in {
    supervised:
      val rateLimiter = RateLimiter.fixedWindow(2, FiniteDuration(1, "second"))

      val operationsRunning = AtomicLong(0L)

      def operation =
        operationsRunning.updateAndGet(_ + 1)
        Thread.sleep(3000L)
        operationsRunning.updateAndGet(_ - 1)
        0
      end operation

      var result1: Option[Int] = Some(-1)
      var result2: Option[Int] = Some(-1)
      var result3: Int = -1
      var resultOperations: Long = 0L

      supervised:
        forkUserDiscard:
          result1 = rateLimiter.runOrDrop(operation)
        forkUserDiscard:
          result2 = rateLimiter.runOrDrop(operation)
        forkUserDiscard:
          result3 = rateLimiter.runBlocking(operation)
        forkUserDiscard:
          // Wait for next window for 3rd operation to start, take number of operations running
          Thread.sleep(1500L)
          resultOperations = operationsRunning.get()

      result1 shouldBe Some(0)
      result2 shouldBe Some(0)
      result3 shouldBe 0
      resultOperations shouldBe 3
  }

  it should "not allow to run more long running operations concurrently than max rate when considering operation time" in {
    supervised:
      val rateLimiter = RateLimiter.fixedWindow(2, FiniteDuration(1, "second"), RateLimiterMode.OperationDuration)

      val operationsRunning = AtomicLong(0L)

      def operation =
        operationsRunning.updateAndGet(_ + 1)
        Thread.sleep(3000L)
        operationsRunning.updateAndGet(_ - 1)
        0

      var result1: Option[Int] = Some(-1)
      var result2: Option[Int] = Some(-1)
      var result3: Int = -1
      var resultOperations: Long = 0L

      supervised:
        forkUserDiscard:
          result1 = rateLimiter.runOrDrop(operation)
        forkUserDiscard:
          result2 = rateLimiter.runOrDrop(operation)
        forkUserDiscard:
          result3 = rateLimiter.runBlocking(operation)
        forkUserDiscard:
          // Wait for next window for 3rd operation to start, take number of operations running
          Thread.sleep(1500L)
          resultOperations = operationsRunning.get()

      result1 shouldBe Some(0)
      result2 shouldBe Some(0)
      result3 shouldBe 0
      resultOperations shouldBe 2
  }

  behavior of "sliding window RateLimiter"
  it should "drop operation when rate limit is exceeded" in {
    supervised {
      val rateLimiter = RateLimiter(
        RateLimiterAlgorithm.SlidingWindow(2, FiniteDuration(1, "second"))
      )

      var executions = 0
      def operation =
        executions += 1
        0

      val result1 = rateLimiter.runOrDrop(operation)
      val result2 = rateLimiter.runOrDrop(operation)
      val result3 = rateLimiter.runOrDrop(operation)

      result1 shouldBe Some(0)
      result2 shouldBe Some(0)
      result3 shouldBe None
      executions shouldBe 2
    }
  }

  it should "restart rate limiter after given duration" in {
    supervised {
      val rateLimiter = RateLimiter(
        RateLimiterAlgorithm.SlidingWindow(2, FiniteDuration(1, "second"))
      )

      var executions = 0
      def operation =
        executions += 1
        0

      val result1 = rateLimiter.runOrDrop(operation)
      val result2 = rateLimiter.runOrDrop(operation)
      val result3 = rateLimiter.runOrDrop(operation)
      ox.sleep(1.second)
      ox.sleep(100.milliseconds) // make sure the rate limiter is replenished
      val result4 = rateLimiter.runOrDrop(operation)

      result1 shouldBe Some(0)
      result2 shouldBe Some(0)
      result3 shouldBe None
      result4 shouldBe Some(0)
      executions shouldBe 3
    }
  }

  it should "block operation when rate limit is exceeded" in {
    supervised {
      val rateLimiter = RateLimiter(
        RateLimiterAlgorithm.SlidingWindow(2, FiniteDuration(1, "second"))
      )

      var executions = 0
      def operation =
        executions += 1
        0

      val ((result1, result2, result3), timeElapsed) = measure {
        val r1 = rateLimiter.runBlocking(operation)
        val r2 = rateLimiter.runBlocking(operation)
        val r3 = rateLimiter.runBlocking(operation)
        (r1, r2, r3)
      }

      result1 shouldBe 0
      result2 shouldBe 0
      result3 shouldBe 0
      timeElapsed.toMillis should be >= 1000L - 10
      executions shouldBe 3
    }
  }

  it should "respect time constraints when blocking" in {
    supervised {
      val rateLimiter = RateLimiter(
        RateLimiterAlgorithm.SlidingWindow(2, FiniteDuration(1, "second"))
      )

      var order = List.empty[Int]
      def operationN(n: Int) =
        rateLimiter.runBlocking {
          order = n :: order
          n
        }

      val time1 = System.currentTimeMillis() // 0 seconds
      val result1 = operationN(1)
      ox.sleep(500.millis)
      val result2 = operationN(2)
      val result3 = operationN(3) // blocks until 1 second elapsed
      val time2 = System.currentTimeMillis() // 1 second
      val result4 = operationN(4)
      val time3 = System.currentTimeMillis() // blocks until 1.5 seconds elapsed

      result1 shouldBe 1
      result2 shouldBe 2
      result3 shouldBe 3
      result4 shouldBe 4
      (time2 - time1) should be >= 1000L - 10
      (time3 - time1) should be >= 1500L - 10
      (time2 - time1) should be <= 1200L
      (time3 - time1) should be <= 1700L
      order should be(List(4, 3, 2, 1))
    }
  }

  it should "respect time constraints when blocking concurrently" in {
    supervised {
      val rateLimiter = RateLimiter(
        RateLimiterAlgorithm.SlidingWindow(2, FiniteDuration(1, "second"))
      )

      val order = new AtomicReference(List.empty[Int])
      def operationN(n: Int) =
        rateLimiter.runBlocking {
          order.updateAndGet(ord => n :: ord)
          n
        }

      val before = System.currentTimeMillis() // 0 seconds
      supervised {
        forkUserDiscard:
          operationN(1)
        forkUserDiscard:
          sleep(300.millis)
          operationN(2)
        forkUserDiscard:
          sleep(400.millis)
          operationN(3)
        forkUserDiscard:
          sleep(700.millis)
          operationN(4)
      }
      val after = System.currentTimeMillis

      (after - before) should be >= 1300L - 10
      (after - before) should be <= 1400L
    }
  }

  behavior of "bucket RateLimiter"

  it should "drop operation when rate limit is exceeded" in {
    supervised {
      val rateLimiter = RateLimiter(
        RateLimiterAlgorithm.LeakyBucket(2, FiniteDuration(1, "second"))
      )

      var executions = 0
      def operation =
        executions += 1
        0

      val result1 = rateLimiter.runOrDrop(operation)
      val result2 = rateLimiter.runOrDrop(operation)

      result1 shouldBe Some(0)
      result2 shouldBe None
      executions shouldBe 1
    }
  }

  it should "refill token after time elapsed from last refill and not before" in {
    supervised {
      val rateLimiter = RateLimiter(
        RateLimiterAlgorithm.LeakyBucket(2, FiniteDuration(1, "second"))
      )

      var executions = 0
      def operation =
        executions += 1
        0

      val result1 = rateLimiter.runOrDrop(operation)
      ox.sleep(500.millis)
      val result2 = rateLimiter.runOrDrop(operation)
      ox.sleep(600.millis)
      val result3 = rateLimiter.runOrDrop(operation)

      result1 shouldBe Some(0)
      result2 shouldBe None
      result3 shouldBe Some(0)
      executions shouldBe 2
    }
  }

  it should "block operation when rate limit is exceeded" in {
    supervised {
      val rateLimiter = RateLimiter(
        RateLimiterAlgorithm.LeakyBucket(2, FiniteDuration(1, "second"))
      )

      var executions = 0
      def operation =
        executions += 1
        0

      val ((result1, result2, result3), timeElapsed) = measure {
        val r1 = rateLimiter.runBlocking(operation)
        val r2 = rateLimiter.runBlocking(operation)
        val r3 = rateLimiter.runBlocking(operation)
        (r1, r2, r3)
      }

      result1 shouldBe 0
      result2 shouldBe 0
      timeElapsed.toMillis should be >= 1000L - 10
      executions shouldBe 3
    }
  }

  it should "respect time constraints when blocking" in {
    supervised {
      val rateLimiter = RateLimiter(
        RateLimiterAlgorithm.LeakyBucket(2, FiniteDuration(1, "second"))
      )

      var order = List.empty[Int]
      def operationN(n: Int) =
        rateLimiter.runBlocking {
          order = n :: order
          n
        }

      val time1 = System.currentTimeMillis() // 0 seconds
      val result1 = operationN(1)
      val result2 = operationN(2)
      val time2 = System.currentTimeMillis() // 1 second
      sleep(2.seconds) // add 2 tokens
      val result3 = operationN(3) // blocks until 1 second elapsed
      val result4 = operationN(4) // blocks until 2 seconds elapsed
      val time3 = System.currentTimeMillis()
      val result5 = operationN(5) // blocks until 2 seconds elapsed
      val time4 = System.currentTimeMillis()

      result1 shouldBe 1
      result2 shouldBe 2
      result3 shouldBe 3
      result4 shouldBe 4
      result5 shouldBe 5
      (time2 - time1) should be >= 1000L - 10
      (time3 - time1) should be >= 3000L - 10
      (time4 - time1) should be >= 4000L - 10
      (time2 - time1) should be <= 1200L
      (time3 - time1) should be <= 3200L
      (time4 - time1) should be <= 4200L
      order should be(List(5, 4, 3, 2, 1))
    }
  }

  it should "respect time constraints when blocking concurrently" in {
    supervised {
      val rateLimiter = RateLimiter(
        RateLimiterAlgorithm.LeakyBucket(2, FiniteDuration(1, "second"))
      )

      val order = new AtomicReference(List.empty[Int])
      def operationN(n: Int) =
        rateLimiter.runBlocking {
          order.updateAndGet(ord => n :: ord)
          n
        }

      val before = System.currentTimeMillis()
      supervised {
        forkUserDiscard:
          operationN(1)
        forkUserDiscard:
          sleep(50.millis)
          operationN(2)
        forkUserDiscard:
          sleep(100.millis)
          operationN(3)
        forkUserDiscard:
          sleep(150.millis)
          operationN(4)
      }
      val after = System.currentTimeMillis()

      (after - before) should be >= 3000L - 10
      (after - before) should be <= 3200L
    }
  }

end RateLimiterTest
