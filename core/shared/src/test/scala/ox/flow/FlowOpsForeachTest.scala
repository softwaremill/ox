package ox.flow

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.*

class FlowOpsForeachTest extends AnyFlatSpec with Matchers:

  behavior of "foreach"

  it should "iterate over a flow" in:
    val c = Flow.fromValues(1, 2, 3)

    var r: List[Int] = Nil
    c.runForeach(v => r = v :: r)

    r shouldBe List(3, 2, 1)

  it should "convert flow to a list" in:
    val c = Flow.fromValues(1, 2, 3)

    c.runToList() shouldBe List(1, 2, 3)
end FlowOpsForeachTest
