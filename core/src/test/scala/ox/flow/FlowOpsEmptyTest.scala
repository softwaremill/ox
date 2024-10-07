package ox.flow

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.*

class FlowOpsEmptyTest extends AnyFlatSpec with Matchers:

  behavior of "empty"

  it should "be empty" in:
    Flow.empty.runToList() shouldBe empty
