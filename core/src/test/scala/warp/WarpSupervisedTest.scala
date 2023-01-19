package warp

import jdk.incubator.concurrent.ScopedValue
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.slf4j.LoggerFactory

import java.time.Clock
import java.util.concurrent.atomic.AtomicInteger

class WarpSupervisedTest extends AnyFlatSpec with Matchers {
  class Trail(var trail: Vector[String] = Vector.empty) {
    def add(s: String): Unit = {
      info(s"[${Clock.systemUTC().instant()}] [${Thread.currentThread().threadId()}] $s")
      trail = trail :+ s
    }
  }

  it should "run two forks concurrently" in {
    val trail = Trail()
    WarpSupervised {
      val f1 = WarpSupervised.fork {
        Thread.sleep(500)
        trail.add("f1 complete")
        5
      }
      val f2 = WarpSupervised.fork {
        Thread.sleep(1000)
        trail.add("f2 complete")
        6
      }
      trail.add("main mid")
      trail.add(s"result = ${f1.join() + f2.join()}")
    }

    trail.trail shouldBe Vector("main mid", "f1 complete", "f2 complete", "result = 11")
  }

  it should "allow nested forks" in {
    val trail = Trail()
    WarpSupervised {
      val f1 = WarpSupervised.fork {
        val f2 = WarpSupervised.fork {
          Thread.sleep(1000)
          trail.add("f2 complete")
          6
        }

        Thread.sleep(500)
        trail.add("f1 complete")
        5 + f2.join()
      }

      trail.add("main mid")
      trail.add(s"result = ${f1.join()}")
    }

    trail.trail shouldBe Vector("main mid", "f1 complete", "f2 complete", "result = 11")
  }

  it should "allow extension method syntax" in {
    import WarpSupervised.syntax.*
    val trail = Trail()
    WarpSupervised {
      val f1 = {
        val f2 = {
          Thread.sleep(1000)
          trail.add("f2 complete")
          6
        }.fork

        Thread.sleep(500)
        trail.add("f1 complete")
        5 + f2.join()
      }.fork

      trail.add("main mid")
      trail.add(s"result = ${f1.join()}")
    }

    trail.trail shouldBe Vector("main mid", "f1 complete", "f2 complete", "result = 11")
  }

  it should "interrupt child fibers when parents complete" in {
    val trail = Trail()
    WarpSupervised {
      val f1 = WarpSupervised.fork {
        WarpSupervised.fork {
          try
            Thread.sleep(1000)
            trail.add("f2 complete")
            6
          catch
            case e: InterruptedException =>
              trail.add("f2 interrupted")
              throw e
        }

        trail.add("f1 complete")
        5
      }

      trail.add("main mid")
      trail.add(s"result = ${f1.join()}")
    }

    trail.trail shouldBe Vector("main mid", "f1 complete", "f2 interrupted", "result = 5")
  }

  it should "properly propagate fiber local values" in {
    val trail = Trail()
    val v = WarpSupervised.FiberLocal("a")
    WarpSupervised {
      val f1 = WarpSupervised.fork {
        v.forkWhere("x") {
          Thread.sleep(100L)
          trail.add(s"In f1 = ${v.get()}")
        }.join()
        v.get()
      }

      val f3 = WarpSupervised.fork {
        v.forkWhere("z") {
          Thread.sleep(100L)
          WarpSupervised.fork {
            Thread.sleep(100L)
            trail.add(s"In f3 = ${v.get()}")
          }.join()
        }.join()
        v.get()
      }

      trail.add("main mid")
      trail.add(s"result = ${f1.join()}")
      trail.add(s"result = ${f3.join()}")
    }

    trail.trail shouldBe Vector("main mid", "In f1 = x", "result = a", "In f3 = z", "result = a")
  }

  it should "when interrupted, wait until uninterruptible blocks complete" in {
    val trail = Trail()
    WarpSupervised {
      val f = WarpSupervised.fork {
        trail.add(s"Fork start")

        WarpSupervised.uninterruptible {
          trail.add(s"Sleep start")
          Thread.sleep(2000)
          trail.add("Sleep done")
        }

        trail.add("Fork done")
      }

      Thread.sleep(100)
      trail.add("Cancelling ...")
      trail.add(s"Cancel result = ${f.cancel()}")
    }

    trail.trail shouldBe Vector("Fork start", "Sleep start", "Cancelling ...", "Sleep done", "Cancel result = Left(java.lang.InterruptedException)")
  }

  it should "retry the specified number of times" in {
    val trail = Trail()
    val counter = new AtomicInteger(2)
    WarpSupervised {
      val f = WarpSupervised.fork {
        WarpSupervised.retry(3, 100L) {
          trail.add(s"trying")
          if counter.getAndDecrement() == 0 then "ok" else throw new RuntimeException("boom")
        }
      }

      Thread.sleep(100)
      trail.add(s"result = ${f.join()}")
    }

    trail.trail shouldBe Vector("trying", "trying", "trying", "result = ok")
  }
}
