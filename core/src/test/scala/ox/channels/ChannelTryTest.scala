package ox.channels

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ChannelTryTest extends AnyFlatSpec with Matchers:
  // trySend

  "trySend" should "return true when there is space in the buffer" in {
    val c = Channel.buffered[Int](2)
    c.trySend(1) shouldBe true
    c.trySend(2) shouldBe true
  }

  it should "return false when the buffer is full" in {
    val c = Channel.buffered[Int](1)
    c.trySend(1) shouldBe true
    c.trySend(2) shouldBe false
  }

  it should "return true for an unlimited channel" in {
    val c = Channel.unlimited[Int]
    c.trySend(1) shouldBe true
    c.trySend(2) shouldBe true
  }

  it should "throw ChannelClosedException.Done when the channel is done" in {
    val c = Channel.buffered[Int](2)
    c.done()
    assertThrows[ChannelClosedException.Done] {
      c.trySend(1)
    }
  }

  it should "throw ChannelClosedException.Error when the channel is in error" in {
    val c = Channel.buffered[Int](2)
    c.error(new RuntimeException("test"))
    assertThrows[ChannelClosedException.Error] {
      c.trySend(1)
    }
  }

  // trySendOrClosed

  "trySendOrClosed" should "return true when there is space in the buffer" in {
    val c = Channel.buffered[Int](2)
    c.trySendOrClosed(1) shouldBe true
    c.trySendOrClosed(2) shouldBe true
  }

  it should "return false when the buffer is full" in {
    val c = Channel.buffered[Int](1)
    c.trySendOrClosed(1) shouldBe true
    c.trySendOrClosed(2) shouldBe false
  }

  it should "return ChannelClosed.Done when the channel is done" in {
    val c = Channel.buffered[Int](2)
    c.done()
    c.trySendOrClosed(1) shouldBe ChannelClosed.Done
  }

  it should "return ChannelClosed.Error when the channel is in error" in {
    val c = Channel.buffered[Int](2)
    val ex = new RuntimeException("test")
    c.error(ex)
    c.trySendOrClosed(1) should matchPattern { case ChannelClosed.Error(_) => }
  }

  // tryReceive

  "tryReceive" should "return Some with the value when one is available" in {
    val c = Channel.buffered[Int](2)
    c.send(1)
    c.send(2)
    c.tryReceive() shouldBe Some(1)
    c.tryReceive() shouldBe Some(2)
  }

  it should "return None when the buffer is empty" in {
    val c = Channel.buffered[Int](2)
    c.tryReceive() shouldBe None
  }

  it should "throw ChannelClosedException.Done when the channel is done and empty" in {
    val c = Channel.buffered[Int](2)
    c.done()
    assertThrows[ChannelClosedException.Done] {
      c.tryReceive()
    }
  }

  it should "throw ChannelClosedException.Error when the channel is in error" in {
    val c = Channel.buffered[Int](2)
    c.send(1)
    c.error(new RuntimeException("test"))
    assertThrows[ChannelClosedException.Error] {
      c.tryReceive()
    }
  }

  // tryReceiveOrClosed

  "tryReceiveOrClosed" should "return Some with the value when one is available" in {
    val c = Channel.buffered[Int](2)
    c.send(1)
    c.tryReceiveOrClosed() shouldBe Some(1)
  }

  it should "return None when the buffer is empty" in {
    val c = Channel.buffered[Int](2)
    c.tryReceiveOrClosed() shouldBe None
  }

  it should "return ChannelClosed.Done when the channel is done and empty" in {
    val c = Channel.buffered[Int](2)
    c.done()
    c.tryReceiveOrClosed() shouldBe ChannelClosed.Done
  }

  it should "return ChannelClosed.Error when the channel is in error" in {
    val c = Channel.buffered[Int](2)
    c.send(1)
    c.error(new RuntimeException("test"))
    c.tryReceiveOrClosed() should matchPattern { case ChannelClosed.Error(_) => }
  }

  it should "return Some(value) from a done channel that still has buffered values" in {
    val c = Channel.buffered[Int](2)
    c.send(42)
    c.done()
    c.tryReceiveOrClosed() shouldBe Some(42)
  }
end ChannelTryTest
