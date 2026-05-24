package ox.channels.jox

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ChannelUnlimitedTest extends AnyFlatSpec with Matchers:
  "unlimited channel" should "never block on send" in:
    val ch = Channel.newUnlimitedChannel[Int]()
    for i <- 1 to 1000 do ch.send(i)
    for i <- 1 to 1000 do ch.receive() shouldBe i

  it should "handle done with buffered values" in:
    val ch = Channel.newUnlimitedChannel[Int]()
    ch.send(1)
    ch.send(2)
    ch.send(3)
    ch.done()
    ch.receive() shouldBe 1
    ch.receive() shouldBe 2
    ch.receive() shouldBe 3
    ch.receiveOrClosed() shouldBe a[ChannelDone]
