package ox.ratelimiter.test

import org.scalatest.concurrent.{Eventually, IntegrationPatience}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.ratelimiter.RateLimiter

import java.time.LocalTime
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration.*

class RateLimiterTest extends AnyFlatSpec with Matchers with Eventually with IntegrationPatience:
  it should "rate limit futures scheduled upfront" in {
    RateLimiter.withRateLimiter(2, 1.second) { rateLimiter =>
      val complete = new AtomicReference(Vector.empty[Int])
      for i <- 1 to 7 do
        rateLimiter.runLimited {
          println(s"${LocalTime.now()} Running $i")
          complete.updateAndGet(_ :+ i)
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
end RateLimiterTest
