package ox.flow

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.*

class FlowOpsFilterTest extends AnyFlatSpec with Matchers:
  behavior of "filter"

  it should "not filter anything from the empty flow" in:
    val c = Flow.empty[Int]
    val s = c.filter(_ % 2 == 0)
    s.runToList() shouldBe List.empty

  it should "filter out everything if no element meets 'f'" in:
    val c = Flow.fromValues(1 to 10: _*)
    val s = c.filter(_ => false)
    s.runToList() shouldBe List.empty

  it should "not filter anything if all the elements meet 'f'" in:
    val c = Flow.fromValues(1 to 10: _*)
    val s = c.filter(_ => true)
    s.runToList() shouldBe (1 to 10)

  it should "filter out elements that don't meet 'f'" in:
    val c = Flow.fromValues(1 to 10: _*)
    val s = c.filter(_ % 2 == 0)
    s.runToList() shouldBe (2 to 10 by 2)

end FlowOpsFilterTest
