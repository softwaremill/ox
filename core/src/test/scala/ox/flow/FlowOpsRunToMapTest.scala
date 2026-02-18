package ox.flow

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class FlowOpsRunToMapTest extends AnyFlatSpec with Matchers:
  behavior of "runToMap"

  it should "return all key-value pairs as a map" in:
    Flow.fromValues("a" -> 1, "b" -> 2, "c" -> 3).runToMap() shouldBe Map("a" -> 1, "b" -> 2, "c" -> 3)

  it should "return an empty map for an empty flow" in:
    Flow.empty[(String, Int)].runToMap() shouldBe Map.empty[String, Int]

  it should "use the last value for duplicate keys" in:
    Flow.fromValues("a" -> 1, "b" -> 2, "a" -> 3).runToMap() shouldBe Map("a" -> 3, "b" -> 2)

  it should "propagate errors" in:
    val flow = Flow.fromValues("a" -> 1).concat(Flow.failed[(String, Int)](new RuntimeException("boom")))
    assertThrows[RuntimeException]:
      flow.runToMap()
end FlowOpsRunToMapTest
