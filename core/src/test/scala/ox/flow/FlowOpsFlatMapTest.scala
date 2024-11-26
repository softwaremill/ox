package ox.flow

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.*

class FlowOpsFlatMapTest extends AnyFlatSpec with Matchers:

  behavior of "flatMap"

  it should "flatten simple flows" in:
    Flow.fromValues(10, 20, 30).flatMap(v => Flow.fromValues(v + 1, v + 2)).runToList() shouldBe List(11, 12, 21, 22, 31, 32)

  it should "propagate errors" in:
    the[RuntimeException] thrownBy {
      Flow
        .fromValues(1, 2, 3)
        .flatMap(v => if v == 2 then Flow.failed(new RuntimeException("boom")) else Flow.fromValues(v + 1, v + 2))
        .runToList()
    } should have message "boom"
end FlowOpsFlatMapTest
