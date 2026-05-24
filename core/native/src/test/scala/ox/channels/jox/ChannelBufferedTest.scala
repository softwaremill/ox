package ox.channels.jox

// Ported from: channels/src/test/java/com/softwaremill/jox/ChannelBufferedTest.java (jox 1.1.2)

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.util.concurrent.ConcurrentSkipListSet

class ChannelBufferedTest extends AnyFlatSpec with Matchers:
  import TestUtil.*

  "buffered channel" should "send and receive without blocking when buffer has space" in:
    val ch = Channel.newBufferedChannel[Int](3)
    ch.send(1)
    ch.send(2)
    ch.send(3)
    ch.receive() shouldBe 1
    ch.receive() shouldBe 2
    ch.receive() shouldBe 3

  it should "block when buffer is full" in scoped { scope =>
    val ch = Channel.newBufferedChannel[Int](2)
    ch.send(1)
    ch.send(2)
    @volatile var sent = false
    forkVoid(scope, () => { ch.send(3); sent = true })
    Thread.sleep(50)
    sent shouldBe false
    ch.receive() shouldBe 1
    Thread.sleep(50)
    sent shouldBe true
    ch.receive() shouldBe 2
    ch.receive() shouldBe 3
  }

  it should "send and receive in many forks" in scoped { scope =>
    val ch = Channel.newBufferedChannel[Int](16)
    val s = new ConcurrentSkipListSet[Int]()

    for i <- 1 to 1000 do forkVoid(scope, () => ch.send(i))
    val fs = (1 to 1000).map(_ => forkVoid(scope, () => { s.add(ch.receive()); () }))
    fs.foreach(_.get())
    s.size() shouldBe 1000
  }

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
    ch.receiveOrClosed() shouldBe a[ChannelError]
