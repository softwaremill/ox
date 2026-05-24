package ox.channels.jox

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ChannelTrySendReceiveTest extends AnyFlatSpec with Matchers:
  "trySendOrClosed" should "return null when value is sent to buffered channel" in:
    val ch = Channel.newBufferedChannel[Int](2)
    ch.trySendOrClosed(1) shouldBe null
    ch.trySendOrClosed(2) shouldBe null

  it should "return sentinel when buffered channel is full" in:
    val ch = Channel.newBufferedChannel[Int](1)
    ch.trySendOrClosed(1) shouldBe null
    ch.trySendOrClosed(2) should be(Channel.TRY_SEND_NOT_SENT)

  it should "return ChannelClosed when channel is closed" in:
    val ch = Channel.newBufferedChannel[Int](1)
    ch.done()
    ch.trySendOrClosed(1) shouldBe a[ChannelClosed]

  it should "send to unlimited channel" in:
    val ch = Channel.newUnlimitedChannel[Int]()
    ch.trySendOrClosed(1) shouldBe null
    ch.trySendOrClosed(2) shouldBe null
    ch.receive() shouldBe 1
    ch.receive() shouldBe 2

  "tryReceiveOrClosed" should "return null when nothing is available" in:
    val ch = Channel.newBufferedChannel[Int](2)
    ch.tryReceiveOrClosed() shouldBe null

  it should "return a value when one is available" in:
    val ch = Channel.newBufferedChannel[Int](2)
    ch.send(42)
    ch.tryReceiveOrClosed() shouldBe 42.asInstanceOf[AnyRef]

  it should "return ChannelClosed when channel is done and empty" in:
    val ch = Channel.newBufferedChannel[Int](2)
    ch.done()
    ch.tryReceiveOrClosed() shouldBe a[ChannelClosed]

  it should "return buffered value even after done" in:
    val ch = Channel.newBufferedChannel[Int](2)
    ch.send(1)
    ch.done()
    ch.tryReceiveOrClosed() shouldBe 1.asInstanceOf[AnyRef]
    ch.tryReceiveOrClosed() shouldBe a[ChannelClosed]
