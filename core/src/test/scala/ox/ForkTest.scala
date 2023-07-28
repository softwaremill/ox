package ox

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.*
import ox.util.Trail

class ForkTest extends AnyFlatSpec with Matchers {
  class CustomException extends RuntimeException

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

  it should "throw the exception thrown by a joined fork" in {
    val trail = Trail()
    try scoped(fork(throw new CustomException()).join())
    catch case e: Exception => trail.add(e.getClass.getSimpleName)

    trail.get shouldBe Vector("CustomException")
  }

  it should "block on cancel until the fork completes" in {
    val trail = Trail()
    scoped {
      val f = fork {
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

  it should "block on cancel until the fork completes (stress test)" in {
    for (_ <- 1 to 10) {
      val trail = Trail()
      scoped {
        val f = fork {
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

        f.cancel()
        trail.add("cancel done")
        Thread.sleep(500L)
      }
      if trail.get.length == 1
      then trail.get shouldBe Vector("cancel done") // the fork wasn't even started
      else trail.get shouldBe Vector("started", "interrupted", "interrupted done", "cancel done")
    }
  }

  "cancelNow" should "return immediately, and wait for forks when scope completes" in {
    val trail = Trail()
    scoped {
      val f = fork {
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
