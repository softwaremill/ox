package ox.resilience

import ox.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.{EitherValues, TryValues}
import ox.util.ElapsedTime
import scala.concurrent.duration._
import java.util.concurrent.atomic.AtomicReference

class GenericRateLimiterTest extends AnyFlatSpec with Matchers with EitherValues with TryValues with ElapsedTime:
  behavior of "fixed rate GenericRateLimiter"

  it should "drop operation when rate limit is exceeded" in {
    val rateLimiter = GenericRateLimiter(
      GenericRateLimiter.Executor.Drop(),
      RateLimiterAlgorithm.FixedRate(2, FiniteDuration(1, "second"))
      )
    given GenericRateLimiter.Strategy.Drop = GenericRateLimiter.Strategy.Drop()

    var executions = 0
    def operation = {
      executions +=1
      0
    }

    val result1 = rateLimiter(operation)
    val result2 = rateLimiter(operation)
    val result3 = rateLimiter(operation)

    result1 shouldBe Some(0)
    result2 shouldBe Some(0)
    result3 shouldBe None
    executions shouldBe 2
  }

    it should "restart rate limiter after given duration" in {
    val rateLimiter = GenericRateLimiter(
      GenericRateLimiter.Executor.Drop(),
      RateLimiterAlgorithm.FixedRate(2, FiniteDuration(1, "second"))
      )

    var executions = 0
    def operation = {
      executions +=1
      0
    }

    val result1 = rateLimiter(operation)
    val result2 = rateLimiter(operation)
    val result3 = rateLimiter(operation)
    ox.sleep(1.second)
    val result4 = rateLimiter(operation)

    result1 shouldBe Some(0)
    result2 shouldBe Some(0)
    result3 shouldBe None
    result4 shouldBe Some(0)
    executions shouldBe 3
  }

  it should "block operation when rate limit is exceeded" in {
    val rateLimiter = GenericRateLimiter(
      GenericRateLimiter.Executor.Block(),
      RateLimiterAlgorithm.FixedRate(2, FiniteDuration(1, "second"))
      )

    var executions = 0
    def operation = {
      executions +=1
      0
    }

    val before = System.currentTimeMillis()
    val result1 = rateLimiter(operation)
    val result2 = rateLimiter(operation)
    val result3 = rateLimiter(operation)
    val after = System.currentTimeMillis()
    
    result1 shouldBe 0
    result2 shouldBe 0
    result3 shouldBe 0
    (after-before) should be >= 1000L
    executions shouldBe 3
  }

  it should "respect queueing order when blocking" in {
    val rateLimiter = GenericRateLimiter(
      GenericRateLimiter.Executor.Block(),
      RateLimiterAlgorithm.FixedRate(2, FiniteDuration(1, "second"))
      )

    var order = List.empty[Int]
    def operationN(n: Int) = {
      rateLimiter {
        order = n :: order
        n
      }
    }

    val time1 = System.currentTimeMillis() // 0 seconds
    val result1 = operationN(1)
    val result2 = operationN(2)
    val result3 = operationN(3) //blocks until 1 second elapsed
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
    (time2-time1) should be >= 1000L - 10
    (time3-time1) should be >= 2000L - 10
    (time4-time1) should be >= 3000L - 10
    (time5-time1) should be >= 4000L - 10
    (time2-time1) should be <= 1200L
    (time3-time1) should be <= 2200L
    (time4-time1) should be <= 3200L
    (time5-time1) should be <= 4200L
    order should be (List(9, 8,7,6,5,4, 3,2,1))
  }

  it should "respect queueing order when blocking concurrently" in {
    val rateLimiter = GenericRateLimiter(
      GenericRateLimiter.Executor.Block(),
      RateLimiterAlgorithm.FixedRate(2, FiniteDuration(1, "second"))
      )

    val order = new AtomicReference(List.empty[Int])
    def operationN(n: Int) = {
      rateLimiter {
        order.updateAndGet(ord => n :: ord)
        n
      }
    }

    val before = System.currentTimeMillis() // 0 seconds
    supervised {
      forkUser:
        operationN(1)
      forkUser:
        sleep(50.millis)
        operationN(2)
      forkUser:
        sleep(100.millis)
        operationN(3)
      forkUser:
        sleep(150.millis)
        operationN(4)
      forkUser:
        sleep(200.millis)
        operationN(5)
      forkUser:
        sleep(250.millis)
        operationN(6)
      forkUser:
        sleep(300.millis)
        operationN(7)
      forkUser:
        sleep(350.millis)
        operationN(8)
      forkUser:
        sleep(400.millis)
        operationN(9)
    }
    val after = System.currentTimeMillis()
    
    (after-before) should be >= 4000L - 10
    (after-before) should be <= 4200L
  }



  behavior of "sliding window GenericRateLimiter"

  it should "drop operation when rate limit is exceeded" in {
    val rateLimiter = GenericRateLimiter(
      GenericRateLimiter.Executor.Drop(),
      RateLimiterAlgorithm.SlidingWindow(2, FiniteDuration(1, "second"))
      )

    var executions = 0
    def operation = {
      executions +=1
      0
    }

    val result1 = rateLimiter(operation)
    val result2 = rateLimiter(operation)
    val result3 = rateLimiter(operation)

    result1 shouldBe Some(0)
    result2 shouldBe Some(0)
    result3 shouldBe None
    executions shouldBe 2
  }

  it should "restart rate limiter after given duration" in {
    val rateLimiter = GenericRateLimiter(
      GenericRateLimiter.Executor.Drop(),
      RateLimiterAlgorithm.SlidingWindow(2, FiniteDuration(1, "second"))
      )


    var executions = 0
    def operation = {
      executions +=1
      0
    }

    val result1 = rateLimiter(operation)
    val result2 = rateLimiter(operation)
    val result3 = rateLimiter(operation)
    ox.sleep(1.second)
    val result4 = rateLimiter(operation)

    result1 shouldBe Some(0)
    result2 shouldBe Some(0)
    result3 shouldBe None
    result4 shouldBe Some(0)
    executions shouldBe 3
  }

  it should "block operation when rate limit is exceeded" in {
    val rateLimiter = GenericRateLimiter(
      GenericRateLimiter.Executor.Block(),
      RateLimiterAlgorithm.SlidingWindow(2, FiniteDuration(1, "second"))
      )

    var executions = 0
    def operation = {
      
      executions +=1
      0
    }

    val ((result1, result2, result3), timeElapsed) = measure {
      val r1 = rateLimiter(operation)
      val r2 = rateLimiter(operation)
      val r3 = rateLimiter(operation)
      (r1, r2, r3)
    }

    result1 shouldBe 0
    result2 shouldBe 0
    result3 shouldBe 0
    timeElapsed.toMillis should be >= 1000L - 10
    executions shouldBe 3
  }

  it should "respect queueing order when blocking" in {
    val rateLimiter = GenericRateLimiter(
      GenericRateLimiter.Executor.Block(),
      RateLimiterAlgorithm.SlidingWindow(2, FiniteDuration(1, "second"))
      )

    var order = List.empty[Int]
    def operationN(n: Int) = {
      rateLimiter {
        order = n :: order
        n
      }
    }

    val time1 = System.currentTimeMillis() // 0 seconds
    val result1 = operationN(1)
    ox.sleep(500.millis)
    val result2 = operationN(2)
    val result3 = operationN(3) //blocks until 1 second elapsed
    val time2 = System.currentTimeMillis() // 1 second
    val result4 = operationN(4)
    val time3 = System.currentTimeMillis() // blocks until 1.5 seconds elapsed

    
    result1 shouldBe 1
    result2 shouldBe 2
    result3 shouldBe 3
    result4 shouldBe 4
    (time2-time1) should be >= 1000L - 10
    (time3-time1) should be >= 1500L - 10
    (time2-time1) should be <= 1200L
    (time3-time1) should be <= 1700L
    order should be (List(4, 3,2,1))
  }

  it should "respect queueing order when blocking concurrently" in {
    val rateLimiter = GenericRateLimiter(
      GenericRateLimiter.Executor.Block(),
      RateLimiterAlgorithm.SlidingWindow(2, FiniteDuration(1, "second"))
      )

    val order = new AtomicReference(List.empty[Int])
    def operationN(n: Int) = {
      rateLimiter {
        order.updateAndGet(ord => n :: ord)
        n
      }
    }

    val before = System.currentTimeMillis() // 0 seconds
    supervised {
      forkUser:
        operationN(1)
      forkUser:
        sleep(300.millis)
        operationN(2)
      forkUser:
        sleep(400.millis)
        operationN(3)
      forkUser:
        sleep(700.millis)
        operationN(4)
    }
    val after = System.currentTimeMillis
    
    (after-before) should be >= 1300L - 10
    (after-before) should be <= 1400L
  }

  behavior of "token bucket GenericRateLimiter"

  it should "drop operation when rate limit is exceeded" in {
    val rateLimiter = GenericRateLimiter(
      GenericRateLimiter.Executor.Drop(),
      RateLimiterAlgorithm.TokenBucket(2, FiniteDuration(1, "second"))
      )

    var executions = 0
    def operation = {
      executions +=1
      0
    }

    val result1 = rateLimiter(operation)
    val result2 = rateLimiter(operation)

    result1 shouldBe Some(0)
    result2 shouldBe None
    executions shouldBe 1
  }

  it should "refill token after time elapsed from last refill and not before" in {
    val rateLimiter = GenericRateLimiter(
      GenericRateLimiter.Executor.Drop(),
      RateLimiterAlgorithm.TokenBucket(2, FiniteDuration(1, "second"))
      )


    var executions = 0
    def operation = {
      executions +=1
      0
    }

    val result1 = rateLimiter(operation)
    ox.sleep(500.millis)
    val result2 = rateLimiter(operation)
    ox.sleep(600.millis)
    val result3 = rateLimiter(operation)

    result1 shouldBe Some(0)
    result2 shouldBe None
    result3 shouldBe Some(0)
    executions shouldBe 2
  }

  it should "block operation when rate limit is exceeded" in {
    val rateLimiter = GenericRateLimiter(
      GenericRateLimiter.Executor.Block(),
      RateLimiterAlgorithm.TokenBucket(2, FiniteDuration(1, "second"))
      )

    var executions = 0
    def operation = {
      executions +=1
      0
    }

    val ((result1, result2, result3), timeElapsed) = measure {
      val r1 = rateLimiter(operation)
      val r2 = rateLimiter(operation)

      val r3 = rateLimiter(operation)
      (r1, r2, r3)
    }

    result1 shouldBe 0
    result2 shouldBe 0
    timeElapsed.toMillis should be >= 1000L - 10
    executions shouldBe 3
  }

  it should "respect queueing order when blocking" in {
    val rateLimiter = GenericRateLimiter(
      GenericRateLimiter.Executor.Block(),
      RateLimiterAlgorithm.TokenBucket(2, FiniteDuration(1, "second"))
      )

    var order = List.empty[Int]
    def operationN(n: Int) = {
      rateLimiter {
        order = n :: order
        n
      }
    }

    val time1 = System.currentTimeMillis() // 0 seconds
    val result1 = operationN(1)
    val result2 = operationN(2)
    val time2 = System.currentTimeMillis() // 1 second
    sleep(2.seconds) //add 2 tokens
    val result3 = operationN(3) //blocks until 1 second elapsed
    val result4 = operationN(4) // blocks until 2 seconds elapsed
    val time3 = System.currentTimeMillis()
    val result5 = operationN(5) // blocks until 2 seconds elapsed
    val time4 = System.currentTimeMillis()
    
    result1 shouldBe 1
    result2 shouldBe 2
    result3 shouldBe 3
    result4 shouldBe 4
    result5 shouldBe 5
    (time2-time1) should be >= 1000L - 10
    (time3-time1) should be >= 3000L - 10
    (time4-time1) should be >= 4000L - 10
    (time2-time1) should be <= 1200L
    (time3-time1) should be <= 3200L
    (time4-time1) should be <= 4200L
    order should be (List(5, 4, 3,2,1))
  }

  it should "respect queueing order when blocking concurrently" in {
    val rateLimiter = GenericRateLimiter(
      GenericRateLimiter.Executor.Block(),
      RateLimiterAlgorithm.TokenBucket(2, FiniteDuration(1, "second"))
      )

    val order = new AtomicReference(List.empty[Int])
    def operationN(n: Int) = {
      rateLimiter {
        order.updateAndGet(ord => n :: ord)
        n
      }
    }


    val before = System.currentTimeMillis()
    supervised {
      forkUser:
        operationN(1)
      forkUser:
        sleep(50.millis)
        operationN(2)
      forkUser:
        sleep(100.millis)
        operationN(3)
      forkUser:
        sleep(150.millis)
        operationN(4)
    }
    val after = System.currentTimeMillis()
    
    (after-before) should be >= 3000L - 10
    (after-before) should be <= 3200L
  }

  behavior of "leaky bucket GenericRateLimiter"

  it should "drop operation when rate limit is exceeded" in {
    val rateLimiter = GenericRateLimiter(
      GenericRateLimiter.Executor.Drop(),
      RateLimiterAlgorithm.LeakyBucket(2, FiniteDuration(1, "second"))
    )

    var executions = 0
    def operation = {
      executions +=1
      0
    }

    val result1 = rateLimiter(operation)
    val result2 = rateLimiter(operation)
    val result3 = rateLimiter(operation)

    result1 shouldBe Some(0)
    result2 shouldBe Some(0)
    result3 shouldBe None
    executions shouldBe 2
  }

  it should "reject operation before leaking and accepting after it" in {
    val rateLimiter = GenericRateLimiter(
      GenericRateLimiter.Executor.Drop(),
      RateLimiterAlgorithm.LeakyBucket(2, FiniteDuration(1, "second"))
    )


    var executions = 0
    def operation = {
      executions +=1
      0
    }

    val result1 = rateLimiter(operation)
    ox.sleep(500.millis)
    val result2 = rateLimiter(operation)
    ox.sleep(400.millis)
    val result3 = rateLimiter(operation)
    ox.sleep(101.millis)
    val result4 = rateLimiter(operation)

    result1 shouldBe Some(0)
    result2 shouldBe Some(0)
    result3 shouldBe None
    result4 shouldBe Some(0)
    executions shouldBe 3
  }

  it should "restart rate limiter after given duration" in {
    val rateLimiter = GenericRateLimiter(
      GenericRateLimiter.Executor.Drop(),
      RateLimiterAlgorithm.LeakyBucket(2, FiniteDuration(1, "second"))
    )


    var executions = 0
    def operation = {
      executions +=1
      0
    }

    val result1 = rateLimiter(operation)
    val result2 = rateLimiter(operation)
    val result3 = rateLimiter(operation)
    ox.sleep(1.second)
    val result4 = rateLimiter(operation)

    result1 shouldBe Some(0)
    result2 shouldBe Some(0)
    result3 shouldBe None
    result4 shouldBe Some(0)
    executions shouldBe 3
  }

  it should "block operation when rate limit is exceeded" in {
    val rateLimiter = GenericRateLimiter(
      GenericRateLimiter.Executor.Block(),
      RateLimiterAlgorithm.LeakyBucket(2, FiniteDuration(1, "second"))
    )

    var executions = 0
    def operation = {
      
      executions +=1
      0
    }

    val ((result1, result2, result3), timeElapsed) = measure {
      val r1 = rateLimiter(operation)
      val r2 = rateLimiter(operation)
      val r3 = rateLimiter(operation)
      (r1, r2, r3)
    }

    result1 shouldBe 0
    result2 shouldBe 0
    result3 shouldBe 0
    timeElapsed.toMillis should be >= 1000L - 10
    executions shouldBe 3
  }

  it should "respect queueing order when blocking" in {
    val rateLimiter = GenericRateLimiter(
      GenericRateLimiter.Executor.Block(),
      RateLimiterAlgorithm.LeakyBucket(2, FiniteDuration(1, "second"))
    )

    var order = List.empty[Int]
    def operationN(n: Int) = {
      rateLimiter {
        order = n :: order
        n
      }
    }

    val time1 = System.currentTimeMillis() // 0 seconds
    val result1 = operationN(1)
    val result2 = operationN(2)
    val result3 = operationN(3) //blocks until 1 second elapsed
    val time2 = System.currentTimeMillis() // 1 second
    val result4 = operationN(4) // blocks until 2 seconds elapsed
    val time3 = System.currentTimeMillis()
    
    result1 shouldBe 1
    result2 shouldBe 2
    result3 shouldBe 3
    result4 shouldBe 4
    (time2-time1) should be >= 1000L - 10
    (time3-time1) should be >= 2000L - 10
    (time2-time1) should be <= 1200L
    (time3-time1) should be <= 2200L
    order should be (List(4, 3,2,1))
  }

  it should "respect queueing order when blocking concurrently" in {
    val rateLimiter = GenericRateLimiter(
      GenericRateLimiter.Executor.Block(),
      RateLimiterAlgorithm.LeakyBucket(2, FiniteDuration(1, "second"))
    )

    val order = new AtomicReference(List.empty[Int])
    def operationN(n: Int) = {
      rateLimiter {
        order.updateAndGet(ord => n :: ord)
        n
      }
    }


    val before = System.currentTimeMillis()
    supervised {
      forkUser:
        operationN(1)
      forkUser:
        sleep(50.millis)
        operationN(2)
      forkUser:
        sleep(100.millis)
        operationN(3)
      forkUser:
        sleep(150.millis)
        operationN(4)
    }
    val after = System.currentTimeMillis()
    
    (after-before) should be >= 2000L - 10
    (after-before) should be <= 2200L
  }

end GenericRateLimiterTest