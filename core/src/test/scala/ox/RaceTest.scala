package ox

import jdk.incubator.concurrent.ScopedValue
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.*
import ox.util.Trail

import java.time.Clock
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
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
}
