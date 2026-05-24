package ox.channels.jox

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ChannelBufferedTest extends AnyFlatSpec with Matchers:
  "buffered channel" should "send and receive without blocking when buffer has space" in:
    val ch = Channel.newBufferedChannel[Int](3)
    ch.send(1)
    ch.send(2)
    ch.send(3)
    ch.receive() shouldBe 1
    ch.receive() shouldBe 2
    ch.receive() shouldBe 3

  it should "block when buffer is full" in:
    val ch = Channel.newBufferedChannel[Int](2)
    ch.send(1)
    ch.send(2)
    @volatile var sent = false
    val t = Thread.ofVirtual().start { () =>
      ch.send(3)
      sent = true
    }
    Thread.sleep(50)
    sent shouldBe false
    ch.receive() shouldBe 1
    t.join()
    sent shouldBe true
    ch.receive() shouldBe 2
    ch.receive() shouldBe 3

  it should "handle done with buffered values" in:
    val ch = Channel.newBufferedChannel[Int](5)
    ch.send(1)
    ch.send(2)
    ch.done()
    ch.receive() shouldBe 1
    ch.receive() shouldBe 2
    ch.receiveOrClosed() shouldBe a[ChannelDone]

  it should "discard buffered values on error" in:
    val ch = Channel.newBufferedChannel[Int](5)
    ch.send(1)
    ch.send(2)
    val ex = new RuntimeException("test error")
    ch.error(ex)
    ch.receiveOrClosed() match
      case e: ChannelError => e.cause shouldBe ex
      case other           => fail(s"Expected ChannelError, got $other")
