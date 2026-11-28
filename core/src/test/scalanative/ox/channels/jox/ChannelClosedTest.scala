package ox.channels.jox

// Ported from: channels/src/test/java/com/softwaremill/jox/ChannelClosedTest.java (jox 1.1.2)

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ChannelClosedTest extends AnyFlatSpec with Matchers:
  import TestUtil.*

  "closed channel" should "report closed with no values when error" in:
    val c = Channel.newRendezvousChannel[Int]()
    val reason = new RuntimeException()
    c.error(reason)
    c.isClosedForReceive shouldBe true
    c.isClosedForSend shouldBe true
    c.receiveOrClosed() shouldBe a[ChannelError]

  it should "report closed with no values when done" in:
    val c = Channel.newRendezvousChannel[Int]()
    c.done()
    c.isClosedForReceive shouldBe true
    c.isClosedForSend shouldBe true
    c.receiveOrClosed() shouldBe a[ChannelDone]

  it should "not be closed for receive when done with suspended sender" in scoped { scope =>
    val c = Channel.newRendezvousChannel[Int]()
    val f = forkCancelable(scope, () => c.send(1))
    try
      Thread.sleep(100)
      c.done()
      c.isClosedForReceive shouldBe false
      c.isClosedForSend shouldBe true
    finally f.cancel()
  }

  it should "not be closed for receive when done with buffered values" in:
    val c = Channel.newBufferedChannel[Int](5)
    c.send(1)
    c.send(2)
    c.done()
    c.isClosedForReceive shouldBe false
    c.isClosedForSend shouldBe true

  it should "be closed for receive when error with buffered values" in:
    val c = Channel.newBufferedChannel[Int](5)
    c.send(1)
    c.send(2)
    c.error(new RuntimeException())
    c.isClosedForReceive shouldBe true
    c.isClosedForSend shouldBe true
end ChannelClosedTest
