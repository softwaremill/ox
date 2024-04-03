package ox

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.*
import ox.util.Trail

import scala.concurrent.duration.*

class LocalTest extends AnyFlatSpec with Matchers {
  "fork locals" should "properly propagate values" in {
    val trail = Trail()
    val v = ForkLocal("a")
    scoped {
      val f1 = fork {
        v.scopedWhere("x") {
          sleep(100.millis)
          trail.add(s"In f1 = ${v.get()}")
        }
        v.get()
      }

      val f3 = fork {
        v.scopedWhere("z") {
          sleep(100.millis)
          fork {
            sleep(100.millis)
            trail.add(s"In f3 = ${v.get()}")
          }.join()
        }
        v.get()
      }

      trail.add("main mid")
      trail.add(s"result = ${f1.join()}")
      trail.add(s"result = ${f3.join()}")
    }

    trail.get shouldBe Vector("main mid", "In f1 = x", "result = a", "In f3 = z", "result = a")
  }

  it should "propagate values across multiple scopes" in {
    val trail = Trail()
    val v = ForkLocal("a")
    scoped {
      fork {
        v.scopedWhere("x") {
          trail.add(s"nested1 = ${v.get()}")

          scoped {
            fork {
              trail.add(s"nested2 = ${v.get()}")
            }.join()
          }
        }
      }.join()

      trail.add(s"outer = ${v.get()}")
    }

    trail.get shouldBe Vector("nested1 = x", "nested2 = x", "outer = a")
  }

  it should "propagate errors from forks created within scoped values" in {
    val v = ForkLocal("v1")
    val caught = intercept[RuntimeException] {
      supervised {
        v.supervisedWhere("v2") {
          forkUser {
            v.get() shouldBe "v2"
            throw new RuntimeException("boom!")
          }
        }
      }
    }

    caught.getMessage shouldBe "boom!"
  }
}
