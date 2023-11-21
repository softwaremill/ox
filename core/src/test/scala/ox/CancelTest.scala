package ox

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.util.Trail

import java.util.concurrent.Semaphore

class CancelTest extends AnyFlatSpec with Matchers {
  class CustomException extends RuntimeException

  class CustomException2 extends RuntimeException

  "cancel" should "block until the fork completes" in {
    val trail = Trail()
    val r = supervised {
      val f = forkCancellable {
        trail.add("started")
        try
          Thread.sleep(500L)
          trail.add("main done")
        catch
          case e: InterruptedException =>
            trail.add("interrupted")
            Thread.sleep(500L)
            trail.add("interrupted done")
            throw e
      }

      Thread.sleep(100L) // making sure the fork starts
      val r = f.cancel()
      trail.add("cancel done")
      Thread.sleep(1000L)
      r
    }
    trail.get shouldBe Vector("started", "interrupted", "interrupted done", "cancel done")
    r should matchPattern { case Left(_: InterruptedException) => }
  }

  it should "block until the fork completes (stress test)" in {
    for (i <- 1 to 20) {
      info(s"iteration $i")
      val trail = Trail()
      val s = Semaphore(0)
      supervised {
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
    supervised {
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

  it should "(when followed by a joinEither) catch InterruptedException with which a fork ends" in {
    val r = supervised {
      val f = forkCancellable {
        Thread.sleep(200)
      }
      Thread.sleep(100)
      f.cancelNow()
      f.joinEither()
    }

    r should matchPattern { case Left(e: InterruptedException) => }
  }
}
