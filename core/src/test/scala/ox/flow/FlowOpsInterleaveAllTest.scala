package ox.flow

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.*

class FlowOpsInterleaveAllTest extends AnyFlatSpec with Matchers:

  behavior of "interleaveAll"

  it should "interleave no sources" in:
    val s = Flow.interleaveAll(List.empty)
    s.runToList() shouldBe empty

  it should "interleave a single flow" in:
    val c = Flow.fromValues(1, 2, 3)
    val s = Flow.interleaveAll(List(c))
    s.runToList() shouldBe List(1, 2, 3)

  it should "interleave multiple flows" in:
    val c1 = Flow.fromValues(1, 2, 3, 4, 5, 6, 7, 8)
    val c2 = Flow.fromValues(10, 20, 30)
    val c3 = Flow.fromValues(100, 200, 300, 400, 500)

    val s = Flow.interleaveAll(List(c1, c2, c3))

    s.runToList() shouldBe List(1, 10, 100, 2, 20, 200, 3, 30, 300, 4, 400, 5, 500, 6, 7, 8)

  it should "interleave multiple flows using custom segment size" in:
    val c1 = Flow.fromValues(1, 2, 3, 4, 5, 6, 7, 8)
    val c2 = Flow.fromValues(10, 20, 30)
    val c3 = Flow.fromValues(100, 200, 300, 400, 500)

    val s = Flow.interleaveAll(List(c1, c2, c3), segmentSize = 2)

    s.runToList() shouldBe List(1, 2, 10, 20, 100, 200, 3, 4, 30, 300, 400, 5, 6, 500, 7, 8)

  it should "interleave multiple flows using custom segment size and complete eagerly" in:
    val c1 = Flow.fromValues(1, 2, 3, 4, 5, 6, 7, 8)
    val c2 = Flow.fromValues(10, 20, 30)
    val c3 = Flow.fromValues(100, 200, 300, 400, 500)

    val s = Flow.interleaveAll(List(c1, c2, c3), segmentSize = 2, eagerComplete = true)

    s.runToList() shouldBe List(1, 2, 10, 20, 100, 200, 3, 4, 30)
end FlowOpsInterleaveAllTest
