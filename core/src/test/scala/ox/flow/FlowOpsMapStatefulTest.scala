package ox.flow

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.*
import ox.channels.BufferCapacity
import ox.channels.ChannelClosed

class FlowOpsMapStatefulTest extends AnyFlatSpec with Matchers:

  behavior of "mapStateful"

  it should "zip with index" in:
    val c = Flow.fromValues("a", "b", "c")
    val s = c.mapStateful(() => 0)((index, element) => (index + 1, (element, index)))
    s.runToList() shouldBe List(("a", 0), ("b", 1), ("c", 2))

  it should "calculate a running total" in:
    val c = Flow.fromValues(1, 2, 3, 4, 5)
    val s = c.mapStateful(() => 0)((sum, element) => (sum + element, sum), Some.apply)
    s.runToList() shouldBe List(0, 1, 3, 6, 10, 15)

  it should "be able to emit different values than incoming ones" in:
    val c = Flow.fromValues(1, 2, 3, 4, 5)
    val s = c.mapStateful(() => 0)((sum, element) => (sum + element, sum.toString), n => Some(n.toString))
    s.runToList() shouldBe List("0", "1", "3", "6", "10", "15")

  it should "propagate errors in the mapping function" in:
    // given
    val flow = Flow.fromValues("a", "b", "c")

    // when
    val flow2 = flow.mapStateful(() => 0) { (index, element) =>
      if index < 2 then (index + 1, element)
      else throw new RuntimeException("boom")
    }

    // then
    supervised:
      val s =
        given BufferCapacity = BufferCapacity(0) // so that the error isn't created too early
        flow2.runToChannel()

      s.receive() shouldBe "a"
      s.receive() shouldBe "b"
      s.receiveOrClosed() should matchPattern:
        case ChannelClosed.Error(reason) if reason.getMessage == "boom" =>

  it should "propagate errors in the completion callback" in:
    // given
    val flow = Flow.fromValues("a", "b", "c")

    // when
    val flow2 = flow.mapStateful(() => 0)((index, element) => (index + 1, element), _ => throw new RuntimeException("boom"))

    // then
    supervised:
      val s =
        given BufferCapacity = BufferCapacity(0) // so that the error isn't created too early
        flow2.runToChannel()

      s.receive() shouldBe "a"
      s.receive() shouldBe "b"
      s.receive() shouldBe "c"
      s.receiveOrClosed() should matchPattern:
        case ChannelClosed.Error(reason) if reason.getMessage == "boom" =>
end FlowOpsMapStatefulTest
