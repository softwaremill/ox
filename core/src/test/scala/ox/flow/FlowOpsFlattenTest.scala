package ox.flow

import org.scalatest.OptionValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.*

class FlowOpsFlattenTest extends AnyFlatSpec with Matchers with OptionValues:

  behavior of "flatten"

  it should "flatten nested flows" in:
    Flow.fromValues(Flow.fromValues(10, 20), Flow.fromValues(30, 40)).flatten.runToList() shouldBe List(10, 20, 30, 40)
end FlowOpsFlattenTest
