package ox

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.*
import ox.util.Trail

import scala.concurrent.TimeoutException
import scala.concurrent.duration.DurationInt
import scala.util.Failure
import scala.util.Try

class ControlTest extends AnyFlatSpec with Matchers:
  "timeout" should "short-circuit a long computation" in {
    val trail = Trail()
    unsupervised {
      try
        timeout(1.second) {
          sleep(2.seconds)
          trail.add("no timeout")
        }
      catch case _: TimeoutException => trail.add("timeout")

      trail.add("done")
      sleep(2.seconds)
    }

    trail.get shouldBe Vector("timeout", "done")
  }

  it should "pass through the exception of failed computation" in {
    val myException = new Throwable("failed computation")

    Try {
      timeout(1.second)(throw myException)
    } shouldBe Failure(myException)
  }

  it should "not interrupt a short computation" in {
    val trail = Trail()
    unsupervised {
      try
        timeout(1.second) {
          sleep(100.millis)
          trail.add("no timeout")
        }
      catch case _: TimeoutException => trail.add("timeout")

      trail.add("done")
      sleep(2.seconds)
    }

    trail.get shouldBe Vector("no timeout", "done")
  }

  it should "block a thread indefinitely" in {
    val trail = Trail()
    supervised {
      forkDiscard {
        never
        trail.add("never happened!")
      }

      sleep(400.millis)
      trail.add("done")
    }

    trail.get shouldBe Vector("done")
  }

  "timeoutOption" should "pass through the exception of failed computation" in {
    val myException = new Throwable("failed computation")

    Try {
      timeoutOption(1.second)(throw myException)
    } shouldBe Failure(myException)
  }

  "timeoutEither" should "pass through the exception of failed computation" in {
    val myException = new Throwable("failed computation")

    Try {
      timeoutEither(1.second, new TimeoutException(s"Timed out after 1 seconds"))(throw myException)
    } shouldBe Failure(myException)
  }
end ControlTest
