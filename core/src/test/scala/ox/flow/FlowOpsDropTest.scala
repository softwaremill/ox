package ox.flow

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.*

class FlowOpsDropTest extends AnyFlatSpec with Matchers:
  behavior of "drop"

  it should "not drop from the empty flow" in:
    val s = Flow.empty[Int]
    s.drop(1).runToList() shouldBe List.empty

  it should "drop elements from the source" in:
    val s = Flow.fromValues(1, 2, 3)
    s.drop(2).runToList() shouldBe List(3)

  it should "return empty source when more elements than source length was dropped" in:
    val s = Flow.fromValues(1, 2)
    s.drop(3).runToList() shouldBe List.empty

  it should "not drop when 'n == 0'" in:
    val s = Flow.fromValues(1, 2, 3)
    s.drop(0).runToList() shouldBe List(1, 2, 3)
end FlowOpsDropTest
