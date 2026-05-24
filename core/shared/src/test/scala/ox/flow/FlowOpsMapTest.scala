package ox.flow

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.*

class FlowOpsMapTest extends AnyFlatSpec with Matchers:

  behavior of "map"

  it should "map over a source" in:
    val c = Flow.fromValues(1, 2, 3)
    val s = c.map(_ * 10)
    s.runToList() shouldBe List(10, 20, 30)

  it should "map over a source using for-syntax" in:
    val c = Flow.fromValues(1, 2, 3)
    val s =
      for v <- c
      yield v * 2
    s.runToList() shouldBe List(2, 4, 6)

end FlowOpsMapTest
