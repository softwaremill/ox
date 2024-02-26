package ox

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.*
import ox.util.Trail

import scala.concurrent.TimeoutException
import scala.concurrent.duration.DurationInt

class ControlTest extends AnyFlatSpec with Matchers {
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

    trail.get shouldBe Vector("timeout", "done")
  }

  it should "not interrupt a short computation" in {
    val trail = Trail()
    scoped {
      try
        timeout(1.second) {
          Thread.sleep(100)
          trail.add("no timeout")
        }
      catch case _: TimeoutException => trail.add("timeout")

      trail.add("done")
      Thread.sleep(2000)
    }

    trail.get shouldBe Vector("no timeout", "done")
  }
}
