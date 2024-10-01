package ox.flow

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.*

class FlowOpsCollectTest extends AnyFlatSpec with Matchers:
  behavior of "collect"

  it should "collect over a source" in:
    val c = Flow.fromValues(1 to 10: _*)

    val s = c.collect {
      case i if i % 2 == 0 => i * 10
    }

    s.runToList() shouldBe (2 to 10 by 2).map(_ * 10)
end FlowOpsCollectTest
