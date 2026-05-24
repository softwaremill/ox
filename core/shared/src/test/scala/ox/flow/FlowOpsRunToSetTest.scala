package ox.flow

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class FlowOpsRunToSetTest extends AnyFlatSpec with Matchers:
  behavior of "runToSet"

  it should "return all elements as a set" in:
    Flow.fromValues(1, 2, 3).runToSet() shouldBe Set(1, 2, 3)

  it should "remove duplicates" in:
    Flow.fromValues(1, 2, 2, 3, 1).runToSet() shouldBe Set(1, 2, 3)

  it should "return an empty set for an empty flow" in:
    Flow.empty[Int].runToSet() shouldBe Set.empty[Int]

  it should "return a single element set" in:
    Flow.fromValues(42).runToSet() shouldBe Set(42)
end FlowOpsRunToSetTest
