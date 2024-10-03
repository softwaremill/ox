package ox.flow

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.*

class FlowOpsUsingSinkTest extends AnyFlatSpec with Matchers:
  behavior of "usingSink"

  it should "send the passed elements" in:
    Flow
      .usingSink(sink =>
        sink(1)
        sink(2)
        sink(3)
      )
      .runToList() shouldBe List(1, 2, 3)
end FlowOpsUsingSinkTest
