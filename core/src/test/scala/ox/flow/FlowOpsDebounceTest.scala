package ox.flow

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.*

class FlowOpsDebounceTest extends AnyFlatSpec with Matchers:
  behavior of "debounce"

  it should "not debounce if applied on an empty flow" in:
    val c = Flow.empty[Int]
    val s = c.debounce
    s.runToList() shouldBe List.empty

  it should "not debounce if applied on a flow containing only distinct values" in:
    val c = Flow.fromValues(1 to 10: _*)
    val s = c.debounce
    s.runToList() shouldBe (1 to 10)

  it should "debounce if applied on a flow containing only repeating values" in:
    val c = Flow.fromValues(1, 1, 1, 1, 1)
    val s = c.debounce
    s.runToList() shouldBe List(1)

  it should "debounce if applied on a flow containing repeating elements" in:
    val c = Flow.fromValues(1, 1, 2, 3, 4, 4, 5)
    val s = c.debounce
    s.runToList() shouldBe (1 to 5)

end FlowOpsDebounceTest
