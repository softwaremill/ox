package ox

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.*
import ox.util.Trail

import scala.concurrent.TimeoutException
import scala.concurrent.duration.DurationInt

class RaceTest extends AnyFlatSpec with Matchers {
  "timeout" should "short-circuit a long computation" in {
    val trail = Trail()
    try
      timeout(1.second) {
        Thread.sleep(2000)
        trail.add("no timeout")
      }
    catch case _: TimeoutException => trail.add("timeout")

    trail.add("done")
    Thread.sleep(2000)

    trail.get shouldBe Vector("timeout", "done")
  }

  it should "not interrupt a short computation" in {
    val trail = Trail()
    try
      timeout(1.second) {
        Thread.sleep(500)
        trail.add("no timeout")
      }
    catch case _: TimeoutException => trail.add("timeout")

    trail.add("done")
    Thread.sleep(2000)

    trail.get shouldBe Vector("no timeout", "done")
  }

  "timeoutOption" should "short-circuit a long computation" in {
    val trail = Trail()
    val result = timeoutOption(1.second) {
      Thread.sleep(2000)
      trail.add("no timeout")
    }

    trail.add(s"done: $result")
    Thread.sleep(2000)

    trail.get shouldBe Vector("done: None")
  }

  "race" should "race a slower and faster computation" in {
    val trail = Trail()
    val start = System.currentTimeMillis()
    race(
      {
        Thread.sleep(1000L)
        trail.add("slow")
      }, {
        Thread.sleep(500L)
        trail.add("fast")
      }
    )
    val end = System.currentTimeMillis()

    Thread.sleep(1000L)
    trail.get shouldBe Vector("fast")
    end - start should be < 1000L
  }

  it should "race a faster and slower computation" in {
    val trail = Trail()
    val start = System.currentTimeMillis()
    race(
      {
        Thread.sleep(500L)
        trail.add("fast")
      }, {
        Thread.sleep(1000L)
        trail.add("slow")
      }
    )
    val end = System.currentTimeMillis()

    Thread.sleep(1000L)
    trail.get shouldBe Vector("fast")
    end - start should be < 1000L
  }

  it should "return the first successful computation to complete" in {
    val trail = Trail()
    val start = System.currentTimeMillis()
    race(
      {
        Thread.sleep(200L)
        trail.add("error")
        throw new RuntimeException("boom!")
      }, {
        Thread.sleep(500L)
        trail.add("slow")
      }, {
        Thread.sleep(1000L)
        trail.add("very slow")
      }
    )
    val end = System.currentTimeMillis()

    Thread.sleep(1000L)
    trail.get shouldBe Vector("error", "slow")
    end - start should be < 1000L
  }

  it should "add other exceptions as suppressed" in {
    try
      race(
        throw new RuntimeException("boom1!"), {
          Thread.sleep(200)
          throw new RuntimeException("boom2!")
        }, {
          Thread.sleep(200)
          throw new RuntimeException("boom3!")
        }
      )
      fail("Race should throw")
    catch
      case e: Exception =>
        e.getMessage shouldBe "boom1!"
        e.getSuppressed.map(_.getMessage).toSet shouldBe Set("boom2!", "boom3!")
  }

  "raceEither" should "return the first successful computation to complete" in {
    val trail = Trail()
    val start = System.currentTimeMillis()
    raceEither(
      {
        Thread.sleep(200L)
        trail.add("error")
        Left(-1)
      }, {
        Thread.sleep(500L)
        trail.add("slow")
        Right("ok")
      }, {
        Thread.sleep(1000L)
        trail.add("very slow")
        Right("also ok")
      }
    )
    val end = System.currentTimeMillis()

    Thread.sleep(1000L)
    trail.get shouldBe Vector("error", "slow")
    end - start should be < 1000L
  }
}
