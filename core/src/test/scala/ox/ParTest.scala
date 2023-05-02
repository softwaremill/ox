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

class ParTest extends AnyFlatSpec with Matchers {
  "par" should "run computations in parallel" in {
    val trail = Trail()
    scoped {
      par {
        Thread.sleep(200)
        trail.add("a")
      } {
        Thread.sleep(100)
        trail.add("b")
      }

      trail.add("done")
    }

    trail.trail shouldBe Vector("b", "a", "done")
  }
}
