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

class ControlTest extends AnyFlatSpec with Matchers {
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
}
