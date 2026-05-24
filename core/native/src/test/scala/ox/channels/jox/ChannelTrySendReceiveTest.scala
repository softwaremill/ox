package ox.channels.jox

// Ported from: channels/src/test/java/com/softwaremill/jox/ChannelTrySendReceiveTest.java (jox 1.1.2)

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ChannelTrySendReceiveTest extends AnyFlatSpec with Matchers:
  import TestUtil.*
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

  "trySend on rendezvous" should "return false when no receiver" in:
    val ch = Channel.newRendezvousChannel[String]()
    val r = ch.trySendOrClosed("a")
    r should not be null
    r should not be a[ChannelClosed]

  it should "send when receiver is waiting" in scoped { scope =>
    val ch = Channel.newRendezvousChannel[String]()
    fork(scope, () => ch.receive())
    var sent = false
    for _ <- 0 until 10 if !sent do
      Thread.sleep(10)
      if ch.trySendOrClosed("x") == null then sent = true
    sent shouldBe true
  }

  "tryReceive on rendezvous" should "return null when no sender" in:
    val ch = Channel.newRendezvousChannel[String]()
    ch.tryReceiveOrClosed() shouldBe null

  it should "receive when sender is waiting" in scoped { scope =>
    val ch = Channel.newRendezvousChannel[String]()
    forkVoid(scope, () => ch.send("x"))
    var received: AnyRef | Null = null
    for _ <- 0 until 10 if received == null do
      Thread.sleep(10)
      val r = ch.tryReceiveOrClosed()
      if r != null && !r.isInstanceOf[ChannelClosed] then received = r
    received shouldBe "x"
  }

  "trySend on unlimited" should "always send" in:
    val ch = Channel.newUnlimitedChannel[String]()
    for i <- 0 until 1000 do ch.trySendOrClosed(s"v$i") shouldBe null

  "trySend on closed" should "throw on done" in:
    val ch = Channel.newBufferedChannel[String](1)
    ch.done()
    ch.trySendOrClosed("x") shouldBe a[ChannelClosed]

  it should "throw on error" in:
    val ch = Channel.newBufferedChannel[String](1)
    ch.error(new RuntimeException("boom"))
    ch.trySendOrClosed("x") shouldBe a[ChannelClosed]

  "trySend with null" should "throw NPE" in:
    val ch = Channel.newBufferedChannel[String](1)
    a[NullPointerException] should be thrownBy ch.trySendOrClosed(null.asInstanceOf[String])
