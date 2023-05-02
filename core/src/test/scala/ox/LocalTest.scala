package ox

import jdk.incubator.concurrent.ScopedValue
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.slf4j.LoggerFactory
import ox.*
import ox.util.Trail

import java.time.Clock
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import scala.concurrent.TimeoutException
import scala.concurrent.duration.DurationInt

class LocalTest extends AnyFlatSpec with Matchers {
  "fork locals" should "properly propagate values" in {
    val trail = Trail()
    val v = ForkLocal("a")
    scoped {
      val f1 = forkHold {
        v.scopedWhere("x") {
          Thread.sleep(100L)
          trail.add(s"In f1 = ${v.get()}")
        }
        v.get()
      }

      val f3 = forkHold {
        v.scopedWhere("z") {
          Thread.sleep(100L)
          forkHold {
            Thread.sleep(100L)
            trail.add(s"In f3 = ${v.get()}")
          }.join()
        }
        v.get()
      }

      trail.add("main mid")
      trail.add(s"result = ${f1.join()}")
      trail.add(s"result = ${f3.join()}")
    }

    trail.trail shouldBe Vector("main mid", "In f1 = x", "result = a", "In f3 = z", "result = a")
  }

  it should "propagate values across multiple scopes" in {
    val trail = Trail()
    val v = ForkLocal("a")
    scoped {
      forkHold {
        v.scopedWhere("x") {
          trail.add(s"nested1 = ${v.get()}")

          scoped {
            forkHold {
              trail.add(s"nested2 = ${v.get()}")
            }.join()
          }
        }
      }.join()

      trail.add(s"outer = ${v.get()}")
    }

    trail.trail shouldBe Vector("nested1 = x", "nested2 = x", "outer = a")
  }
}
