package ox.flow

import org.scalatest.concurrent.Eventually
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.*

import scala.concurrent.duration.DurationInt

class FlowOpsTimeoutTest extends AnyFlatSpec with Matchers with Eventually:
  it should "timeout" in:
    val start = System.currentTimeMillis()
    val c = Flow.timeout(100.millis).concat(Flow.fromValues(1))
    c.runToList() shouldBe List(1)
    (System.currentTimeMillis() - start) shouldBe >=(100L)
    (System.currentTimeMillis() - start) shouldBe <=(150L)
end FlowOpsTimeoutTest
