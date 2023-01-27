package ox.ratelimiter.test

import org.scalatest.concurrent.{Eventually, IntegrationPatience}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.Ox.{fork, scoped}
import ox.ratelimiter.RateLimiter

import java.time.LocalTime
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration.*

class RateLimiterTest extends AnyFlatSpec with Matchers with Eventually with IntegrationPatience:
  it should "rate limit futures scheduled upfront" in {
    RateLimiter.withRateLimiter(2, 1.second) { rateLimiter =>
      val complete = new AtomicReference(Vector.empty[Int])
      for (i <- 1 to 7) {
        rateLimiter.runLimited {
          println(s"${LocalTime.now()} Running $i")
          complete.updateAndGet(_ :+ i)
        }
      }

      eventually {
        complete.get() should have size (7)
        complete.get().slice(0, 2).toSet should be(Set(1, 2))
        complete.get().slice(2, 4).toSet should be(Set(3, 4))
        complete.get().slice(4, 6).toSet should be(Set(5, 6))
        complete.get().slice(6, 7).toSet should be(Set(7))
      }
    }
  }

  it should "maintain the rate limit in all time windows" in {
    RateLimiter.withRateLimiter(10, 1.second) { rateLimiter =>
      val complete = new AtomicReference(Vector.empty[Long])
      for (i <- 1 to 20) {
        rateLimiter.runLimited {
          println(s"${LocalTime.now()} Running $i")
          complete.updateAndGet(_ :+ System.currentTimeMillis())
        }
        Thread.sleep(100)
      }

      eventually {
        complete.get() should have size (20)

        // the spacing should be preserved. In a token bucket algorithm, at some point the bucket would refill and
        // all pending futures would be run. In a windowed algorithm, the spacings are preserved.
        val secondHalf = complete.get().slice(10, 20)
        secondHalf.zip(secondHalf.tail).map { case (p, n) => n - p }.foreach { d =>
          d should be <= (150L)
          d should be >= (50L)
        }
      }
    }
  }
