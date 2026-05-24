package ox.flow

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.*
import ox.channels.ChannelClosed
import ox.channels.Channel

class FlowOpsRunToChannelTest extends AnyFlatSpec with Matchers:
  behavior of "runToChannel"

  it should "receive the elements in the flow" in supervised:
    val ch = Flow.fromValues(1, 2).runToChannel()
    ch.receive() shouldBe 1
    ch.receive() shouldBe 2
    ch.receiveOrClosed() shouldBe ChannelClosed.Done

  it should "return the original source when running a source-backed flow" in supervised:
    val ch = Channel.buffered[Int](16)
    ch.send(1)
    ch.send(2)
    ch.send(3)

    val ch2 = Flow.fromSource(ch).runToChannel()
    ch.eq(ch2) shouldBe true // checking if the optimization is in place

    ch2.receive() shouldBe 1
end FlowOpsRunToChannelTest
