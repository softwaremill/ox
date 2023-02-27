package ox

import jdk.incubator.concurrent.ScopedValue
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.slf4j.LoggerFactory
import ox.Ox.{forkHold, fork, retry, scoped, timeout, uninterruptible}

import java.time.Clock
import java.util.concurrent.atomic.AtomicInteger
import concurrent.duration.DurationInt
import scala.concurrent.TimeoutException

class OxTest extends AnyFlatSpec with Matchers {
  class Trail(var trail: Vector[String] = Vector.empty) {
    def add(s: String): Unit = {
      info(s"[${Clock.systemUTC().instant()}] [${Thread.currentThread().threadId()}] $s")
      trail = trail :+ s
    }
  }

  class CustomException extends RuntimeException

  "forkHold" should "run two forks concurrently" in {
    val trail = Trail()
    scoped {
      val f1 = forkHold {
        Thread.sleep(500)
        trail.add("f1 complete")
        5
      }
      val f2 = forkHold {
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
    scoped {
      val f1 = forkHold {
        val f2 = forkHold {
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
    import Ox.syntax.*
    val trail = Trail()
    scoped {
      val f1 = {
        val f2 = {
          Thread.sleep(1000)
          trail.add("f2 complete")
          6
        }.forkHold

        Thread.sleep(500)
        trail.add("f1 complete")
        5 + f2.join()
      }.forkHold

      trail.add("main mid")
      trail.add(s"result = ${f1.join()}")
    }

    trail.trail shouldBe Vector("main mid", "f1 complete", "f2 complete", "result = 11")
  }

  it should "interrupt child forks when parents complete" in {
    val trail = Trail()
    scoped {
      val f1 = forkHold {
        forkHold {
          try
            Thread.sleep(1000)
            trail.add("f2 complete")
            6
          catch
            case e: InterruptedException =>
              trail.add("f2 interrupted")
              throw e
        }

        Thread.sleep(500)
        trail.add("f1 complete")
        5
      }

      trail.add("main mid")
      trail.add(s"result = ${f1.join()}")
    }

    trail.trail shouldBe Vector("main mid", "f1 complete", "result = 5", "f2 interrupted")
  }

  it should "throw the exception thrown by a joined fork" in {
    val trail = Trail()
    try scoped(forkHold(throw new CustomException()).join())
    catch case e: Exception => trail.add(e.getClass.getSimpleName)

    trail.trail shouldBe Vector("CustomException")
  }

  "fork locals" should "properly propagate values" in {
    val trail = Trail()
    val v = Ox.ForkLocal("a")
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
    val v = Ox.ForkLocal("a")
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

  "uninterruptible" should "when interrupted, wait until block completes" in {
    val trail = Trail()
    scoped {
      val f = forkHold {
        trail.add(s"Fork start")

        uninterruptible {
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

    trail.trail shouldBe Vector(
      "Fork start",
      "Sleep start",
      "Cancelling ...",
      "Sleep done",
      "Cancel result = Left(java.lang.InterruptedException)"
    )
  }

  "retry" should "retry the specified number of times" in {
    val trail = Trail()
    val counter = new AtomicInteger(2)
    scoped {
      val f = forkHold {
        retry(3, 100.milliseconds) {
          trail.add(s"trying")
          if counter.getAndDecrement() == 0 then "ok" else throw new RuntimeException("boom")
        }
      }

      Thread.sleep(100)
      trail.add(s"result = ${f.join()}")
    }

    trail.trail shouldBe Vector("trying", "trying", "trying", "result = ok")
  }

  "timeout" should "short-circuit a long computation" in {
    val trail = Trail()
    scoped {
      try
        timeout(1.second) {
          Thread.sleep(2000)
          trail.add("no timeout")
        }
      catch case _: TimeoutException => trail.add("timeout")

      trail.add("done")
      Thread.sleep(2000)
    }

    trail.trail shouldBe Vector("timeout", "done")
  }

  it should "not interrupt a short computation" in {
    val trail = Trail()
    scoped {
      try
        timeout(1.second) {
          Thread.sleep(500)
          trail.add("no timeout")
        }
      catch case _: TimeoutException => trail.add("timeout")

      trail.add("done")
      Thread.sleep(2000)
    }

    trail.trail shouldBe Vector("no timeout", "done")
  }

  "fork" should "propagate failures to the scope thread" in {
    val trail = Trail()
    try
      scoped {
        val f1 = fork {
          Thread.sleep(2000)
          trail.add("f1 done")
        }

        val f2 = fork {
          Thread.sleep(1000)
          throw new CustomException
        }

        f1.join()
        f2.join()
      }
    catch case e: Exception => trail.add(e.getClass.getSimpleName)

    trail.trail shouldBe Vector("CustomException")
  }

  it should "not propagate interrupt exceptions" in {
    val trail = Trail()
    try
      scoped {
        fork {
          Thread.sleep(2000)
          trail.add("f1 done")
        }

        trail.add("main done")
      }
    catch case e: Exception => trail.add(e.getClass.getSimpleName)

    // child should be interrupted, but the error shouldn't propagate
    trail.trail shouldBe Vector("main done")
  }
}

@main def test1 =
  val log = LoggerFactory.getLogger("test1")
  val r = Ox.scoped {
    val f1 = forkHold {
      Thread.sleep(1000L)
      log.info("f1 done")
      5
    }
    val f2 = forkHold {
      Thread.sleep(2000L)
      log.info("f2 done")
      6
    }
    f1.join() + f2.join()
  }
  log.info("result: " + r)
