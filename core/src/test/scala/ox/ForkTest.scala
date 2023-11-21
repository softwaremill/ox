package ox

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.*
import ox.util.Trail

import java.util.concurrent.Semaphore

class ForkTest extends AnyFlatSpec with Matchers {
  "fork" should "run two forks concurrently" in {
    val trail = Trail()
    scoped {
      val f1 = fork {
        Thread.sleep(500)
        trail.add("f1 complete")
        5
      }
      val f2 = fork {
        Thread.sleep(1000)
        trail.add("f2 complete")
        6
      }
      trail.add("main mid")
      trail.add(s"result = ${f1.join() + f2.join()}")
    }

    trail.get shouldBe Vector("main mid", "f1 complete", "f2 complete", "result = 11")
  }

  it should "allow nested forks" in {
    val trail = Trail()
    scoped {
      val f1 = fork {
        val f2 = fork {
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

    trail.get shouldBe Vector("main mid", "f1 complete", "f2 complete", "result = 11")
  }

  it should "allow extension method syntax" in {
    import ox.syntax.*
    val trail = Trail()
    scoped {
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

    trail.get shouldBe Vector("main mid", "f1 complete", "f2 complete", "result = 11")
  }

  it should "interrupt child forks when parents complete" in {
    val trail = Trail()
    scoped {
      val f1 = fork {
        fork {
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

    trail.get shouldBe Vector("main mid", "f1 complete", "result = 5", "f2 interrupted")
  }

  "cancel" should "block until the fork completes" in {
    val trail = Trail()
    scoped {
      val f = forkCancellable {
        trail.add("started")
        try
          Thread.sleep(500L)
          trail.add("main done")
        catch
          case _: InterruptedException =>
            trail.add("interrupted")
            Thread.sleep(500L)
            trail.add("interrupted done")
      }

      Thread.sleep(100L) // making sure the fork starts
      f.cancel()
      trail.add("cancel done")
      Thread.sleep(1000L)
    }
    trail.get shouldBe Vector("started", "interrupted", "interrupted done", "cancel done")
  }

  it should "block until the fork completes (stress test)" in {
    for (i <- 1 to 20) {
      info(s"iteration $i")
      val trail = Trail()
      val s = Semaphore(0)
      scoped {
        val f = forkCancellable {
          // trail.add("started") // uncommenting this makes interruption to get swallowed by SBT's runner from time to time ...
          try
            s.acquire()
            trail.add("main done")
          catch
            case _: InterruptedException =>
              trail.add("interrupted")
              Thread.sleep(100L)
              trail.add("interrupted done")
        }

        if i % 2 == 0 then Thread.sleep(1) // interleave immediate cancels and after the fork starts (probably)
        f.cancel()
        s.release(1) // the acquire should be interrupted
        trail.add("cancel done")
        Thread.sleep(100L)
      }
      if trail.get.length == 1
      then trail.get shouldBe Vector("cancel done") // the fork wasn't even started
      else trail.get shouldBe Vector("interrupted", "interrupted done", "cancel done")
    }
  }

  "cancelNow" should "return immediately, and wait for forks when scope completes" in {
    val trail = Trail()
    scoped {
      val f = forkCancellable {
        try
          Thread.sleep(500L)
          trail.add("main done")
        catch
          case _: InterruptedException =>
            Thread.sleep(500L)
            trail.add("interrupted done")
      }

      Thread.sleep(100L) // making sure the fork starts
      f.cancelNow()
      trail.add("cancel done")
      trail.get shouldBe Vector("cancel done")
    }
    trail.get shouldBe Vector("cancel done", "interrupted done")
  }
}
