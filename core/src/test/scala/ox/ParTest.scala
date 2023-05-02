package ox

import jdk.incubator.concurrent.ScopedValue
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.util.Trail

import java.time.Clock
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import scala.concurrent.TimeoutException
import scala.concurrent.duration.DurationInt

class ParTest extends AnyFlatSpec with Matchers {
  "par" should "run computations in parallel" in {
    val trail = Trail()
    par {
      Thread.sleep(200)
      trail.add("a")
    } {
      Thread.sleep(100)
      trail.add("b")
    }

    trail.add("done")

    trail.trail shouldBe Vector("b", "a", "done")
  }

  "parLimit" should "run up to the given number of computations in parallel" in {
    val trail = Trail()
    scoped {
      fork {
        forever {
          Thread.sleep(200)
          trail.add("y")
        }
      }

      Thread.sleep(100)
      parLimit(2)(
        (1 to 5).map(_ =>
          () => {
            Thread.sleep(200)
            trail.add("x")
          }
        )
      )
    }

    trail.trail shouldBe Vector("y", "x", "x", "y", "x", "x", "y", "x")
  }
}
