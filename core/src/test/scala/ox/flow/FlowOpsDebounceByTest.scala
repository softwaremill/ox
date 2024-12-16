package ox.flow

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.*

class FlowOpsDebounceByTest extends AnyFlatSpec with Matchers:
  behavior of "debounceBy"

  it should "not debounce if applied on an empty flow" in:
    val c = Flow.empty[Int]
    val s = c.debounceBy(_ * 2)
    s.runToList() shouldBe List.empty

  it should "not debounce if applied on a flow containing only distinct values" in:
    val c = Flow.fromValues(1 to 10: _*)
    val s = c.debounceBy(_ * 2)
    s.runToList() shouldBe (1 to 10)

  it should "debounce if applied on a flow containing repeating elements" in:
    val c = Flow.fromValues(1, 1, 2, 3, 4, 4, 5)
    val s = c.debounceBy(_ * 2)
    s.runToList() shouldBe (1 to 5)

  it should "debounce subsequent odd/prime numbers" in:
    val c = Flow.fromValues(1, 1, 1, 2, 4, 3, 7, 4, 5)
    val s = c.debounceBy(_ % 2 == 0)
    s.runToList() shouldBe List(1, 2, 3, 4, 5)

end FlowOpsDebounceByTest
