package ox

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.*
import ox.util.Trail

import scala.concurrent.duration.*

class LocalTest extends AnyFlatSpec with Matchers:
  "fork locals" should "properly propagate values using supervisedWhere" in {
    val trail = Trail()
    val v = ForkLocal("a")
    unsupervised {
      val f1 = forkUnsupervised {
        v.supervisedWhere("x") {
          sleep(100.millis)
          trail.add(s"In f1 = ${v.get()}")
        }
        v.get()
      }

      val f3 = forkUnsupervised {
        v.supervisedWhere("z") {
          sleep(100.millis)
          forkUnsupervised {
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

  it should "properly propagate values using unsupervisedWhere" in {
    val trail = Trail()
    val v = ForkLocal("a")
    unsupervised {
      val f1 = forkUnsupervised {
        v.unsupervisedWhere("x") {
          sleep(100.millis)
          trail.add(s"In f1 = ${v.get()}")
        }
        v.get()
      }

      val f3 = forkUnsupervised {
        v.unsupervisedWhere("z") {
          sleep(100.millis)
          forkUnsupervised {
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
    unsupervised {
      forkUnsupervised {
        v.supervisedWhere("x") {
          trail.add(s"nested1 = ${v.get()}")

          unsupervised {
            forkUnsupervised {
              trail.add(s"nested2 = ${v.get()}")
            }.join()
          }
        }
      }.join()

      trail.add(s"outer = ${v.get()}")
    }

    trail.get shouldBe Vector("nested1 = x", "nested2 = x", "outer = a")
  }

  it should "propagate errors from forks created within local values" in {
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

  it should "correctly set & unset fork locals when an exception is thrown" in {
    val trail = Trail()
    val v = ForkLocal("v1")
    unsupervised {
      trail.add(v.get())

      try
        v.unsupervisedWhere("v2") {
          trail.add(v.get())
          throw new RuntimeException("boom!")
        }
      catch case e: Exception => trail.add(e.getClass.getSimpleName())

      trail.add(v.get())
    }

    trail.get shouldBe Vector("v1", "v2", "RuntimeException", "v1")
  }

  it should "correctly set & unset multiple fork locals" in {
    val trail = Trail()
    val l1 = ForkLocal("v1_1")
    val l2 = ForkLocal("v2_1")
    unsupervised {
      trail.add(l1.get())
      trail.add(l2.get())

      l1.unsupervisedWhere("v1_2") {
        l2.unsupervisedWhere("v2_2") {

          trail.add(l1.get())
          trail.add(l2.get())
        }
      }

      trail.add(l1.get())
      trail.add(l2.get())
    }

    trail.get shouldBe Vector("v1_1", "v2_1", "v1_2", "v2_2", "v1_1", "v2_1")
  }
end LocalTest
