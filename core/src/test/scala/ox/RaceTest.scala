package ox

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.*
import ox.util.Trail

import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.TimeoutException
import scala.concurrent.duration.DurationInt
import scala.util.control.ControlThrowable

class RaceTest extends AnyFlatSpec with Matchers {
  "timeout" should "short-circuit a long computation" in {
    val trail = Trail()
    try
      timeout(1.second) {
        sleep(2.seconds)
        trail.add("no timeout")
      }
    catch case _: TimeoutException => trail.add("timeout")

    trail.add("done")
    sleep(2.seconds)

    trail.get shouldBe Vector("timeout", "done")
  }

  it should "not interrupt a short computation" in {
    val trail = Trail()
    try
      timeout(1.second) {
        sleep(500.millis)
        trail.add("no timeout")
      }
    catch case _: TimeoutException => trail.add("timeout")

    trail.add("done")
    sleep(2.seconds)

    trail.get shouldBe Vector("no timeout", "done")
  }

  "timeoutOption" should "short-circuit a long computation" in {
    val trail = Trail()
    val result = timeoutOption(1.second) {
      sleep(2.seconds)
      trail.add("no timeout")
    }

    trail.add(s"done: $result")
    sleep(2.seconds)

    trail.get shouldBe Vector("done: None")
  }

  "race" should "race a slower and faster computation" in {
    val trail = Trail()
    val start = System.currentTimeMillis()
    race(
      {
        sleep(1.second)
        trail.add("slow")
      }, {
        sleep(500.millis)
        trail.add("fast")
      }
    )
    val end = System.currentTimeMillis()

    sleep(1.second)
    trail.get shouldBe Vector("fast")
    end - start should be < 1000L
  }

  it should "race a faster and slower computation" in {
    val trail = Trail()
    val start = System.currentTimeMillis()
    race(
      {
        sleep(500.millis)
        trail.add("fast")
      }, {
        sleep(1.second)
        trail.add("slow")
      }
    )
    val end = System.currentTimeMillis()

    sleep(1.second)
    trail.get shouldBe Vector("fast")
    end - start should be < 1000L
  }

  it should "return the first successful computation to complete" in {
    val trail = Trail()
    val start = System.currentTimeMillis()
    race(
      {
        sleep(200.millis)
        trail.add("error")
        throw new RuntimeException("boom!")
      }, {
        sleep(500.millis)
        trail.add("slow")
      }, {
        sleep(1.second)
        trail.add("very slow")
      }
    )
    val end = System.currentTimeMillis()

    sleep(1.second)
    trail.get shouldBe Vector("error", "slow")
    end - start should be < 1000L
  }

  it should "add other exceptions as suppressed" in {
    try
      race(
        throw new RuntimeException("boom1!"), {
          sleep(200.millis)
          throw new RuntimeException("boom2!")
        }, {
          sleep(200.millis)
          throw new RuntimeException("boom3!")
        }
      )
      fail("Race should throw")
    catch
      case e: Exception =>
        e.getMessage shouldBe "boom1!"
        e.getSuppressed.map(_.getMessage).toSet shouldBe Set("boom2!", "boom3!")
  }

  class NastyControlThrowable(val message: String) extends ControlThrowable(message) {}

  it should "treat ControlThrowable as a non-fatal exception" in {
    try
      race(
        throw new NastyControlThrowable("boom1!"), {
          sleep(200.millis)
          throw new NastyControlThrowable("boom2!")
        }, {
          sleep(200.millis)
          throw new NastyControlThrowable("boom3!")
        }
      )
      fail("Race should throw")
    catch
      case e: Throwable =>
        e.getMessage shouldBe "boom1!"
      // Suppressed exceptions are not available for ControlThrowable
  }

  it should "immediately rethrow other fatal exceptions" in {
    val flag = new AtomicBoolean(false)
    try
      race(
        throw new StackOverflowError(), {
          sleep(1.second)
          flag.set(true)
          throw new RuntimeException()
        }
      )
      fail("Race should throw")
    catch
      case e: StackOverflowError => // the expected exception
        flag.get() shouldBe false // because a fatal exception was thrown, the second computation should be interrupted
  }

  "raceEither" should "return the first successful computation to complete" in {
    val trail = Trail()
    val start = System.currentTimeMillis()
    raceEither(
      {
        sleep(200.millis)
        trail.add("error")
        Left(-1)
      }, {
        sleep(500.millis)
        trail.add("slow")
        Right("ok")
      }, {
        sleep(1.second)
        trail.add("very slow")
        Right("also ok")
      }
    )
    val end = System.currentTimeMillis()

    sleep(1.second)
    trail.get shouldBe Vector("error", "slow")
    end - start should be < 1000L
  }
}
