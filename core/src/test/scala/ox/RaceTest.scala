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

    trail.trail shouldBe Vector("timeout", "done")
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

    trail.trail shouldBe Vector("no timeout", "done")
  }

  "timeoutOption" should "short-circuit a long computation" in {
    val trail = Trail()
    val result = timeoutOption(1.second) {
      Thread.sleep(2000)
      trail.add("no timeout")
    }

    trail.add(s"done: $result")
    Thread.sleep(2000)

    trail.trail shouldBe Vector("done: None")
  }

  it should "race a slower and faster computation" in {
    val trail = Trail()
    val start = System.currentTimeMillis()
    raceSuccess {
      Thread.sleep(1000L)
      trail.add("slow")
    } {
      Thread.sleep(500L)
      trail.add("fast")
    }
    val end = System.currentTimeMillis()

    Thread.sleep(1000L)
    trail.trail shouldBe Vector("fast")
    end - start should be < 1000L
  }

  it should "race a faster and slower computation" in {
    val trail = Trail()
    val start = System.currentTimeMillis()
    raceSuccess {
      Thread.sleep(500L)
      trail.add("fast")
    } {
      Thread.sleep(1000L)
      trail.add("slow")
    }
    val end = System.currentTimeMillis()

    Thread.sleep(1000L)
    trail.trail shouldBe Vector("fast")
    end - start should be < 1000L
  }
}
