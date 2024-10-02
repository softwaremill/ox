package ox.flow

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.*
import ox.channels.StageCapacity
import ox.channels.ChannelClosed

class FlowOpsMapConcatTest extends AnyFlatSpec with Matchers:

  behavior of "mapConcat"

  it should "unfold iterables" in:
    val c = Flow.fromValues(List("a", "b", "c"), List("d", "e"), List("f"))
    val s = c.mapConcat(identity)
    s.runToList() shouldBe List("a", "b", "c", "d", "e", "f")

  it should "transform elements" in:
    val c = Flow.fromValues("ab", "cd")
    val s = c.mapConcat { str => str.toList }

    s.runToList() shouldBe List('a', 'b', 'c', 'd')

  it should "handle empty lists" in:
    val c = Flow.fromValues(List.empty, List("a"), List.empty, List("b", "c"))
    val s = c.mapConcat(identity)

    s.runToList() shouldBe List("a", "b", "c")

  it should "propagate errors in the mapping function" in:
    // given
    val flow = Flow.fromValues(List("a"), List("b", "c"), List("error here"))

    // when
    val flow2 = flow.mapConcat { element =>
      if element != List("error here") then element
      else throw new RuntimeException("boom")
    }

    // then
    supervised:
      given StageCapacity = StageCapacity(0) // so that the error isn't created too early
      val s = flow2.runToChannel()
      s.receive() shouldBe "a"
      s.receive() shouldBe "b"
      s.receive() shouldBe "c"
      s.receiveOrClosed() should matchPattern:
        case ChannelClosed.Error(reason) if reason.getMessage == "boom" =>
end FlowOpsMapConcatTest
