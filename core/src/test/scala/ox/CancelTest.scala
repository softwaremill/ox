package ox

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.util.Trail

import java.util.concurrent.Semaphore

import scala.concurrent.duration.*

class CancelTest extends AnyFlatSpec with Matchers {
  "cancel" should "block until the fork completes" in {
    val trail = Trail()
    val r = supervised {
      val f = forkCancellable {
        trail.add("started")
        try
          sleep(500.millis)
          trail.add("main done")
        catch
          case e: InterruptedException =>
            trail.add("interrupted")
            sleep(500.millis)
            trail.add("interrupted done")
            throw e
      }

      sleep(100.millis) // making sure the fork starts
      val r = f.cancel()
      trail.add("cancel done")
      sleep(1.second)
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
              sleep(100.millis)
              trail.add("interrupted done")
        }

        if i % 2 == 0 then sleep(1.millis) // interleave immediate cancels and after the fork starts (probably)
        f.cancel()
        s.release(1) // the acquire should be interrupted
        trail.add("cancel done")
        sleep(100.millis)
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
          sleep(500.millis)
          trail.add("main done")
        catch
          case _: InterruptedException =>
            sleep(500.millis)
            trail.add("interrupted done")
      }

      sleep(100.millis) // making sure the fork starts
      f.cancelNow()
      trail.add("cancel done")
      trail.get shouldBe Vector("cancel done")
    }
    trail.get shouldBe Vector("cancel done", "interrupted done")
  }

  it should "(when followed by a joinEither) catch InterruptedException with which a fork ends" in {
    val r = supervised {
      val f = forkCancellable {
        sleep(200.millis)
      }
      sleep(100.millis)
      f.cancelNow()
      f.joinEither()
    }

    r should matchPattern { case Left(e: InterruptedException) => }
  }
}
