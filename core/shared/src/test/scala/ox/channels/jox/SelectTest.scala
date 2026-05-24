package ox.channels.jox

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class SelectTest extends AnyFlatSpec with Matchers:
  "select" should "receive from the first available channel" in:
    val ch1 = Channel.newBufferedChannel[Int](1)
    val ch2 = Channel.newBufferedChannel[String](1)
    ch1.send(42)
    val result = Select.selectOrClosed(ch1.receiveClause(), ch2.receiveClause())
    result shouldBe Integer.valueOf(42)

  it should "receive from the second channel if first is empty" in:
    val ch1 = Channel.newRendezvousChannel[Int]()
    val ch2 = Channel.newBufferedChannel[String](1)
    ch2.send("hello")
    val result = Select.selectOrClosed(ch1.receiveClause(), ch2.receiveClause())
    result shouldBe "hello"

  it should "select default when no channel is ready" in:
    val ch1 = Channel.newRendezvousChannel[Int]()
    val ch2 = Channel.newRendezvousChannel[String]()
    val result = Select.selectOrClosed(
      ch1.receiveClause(),
      ch2.receiveClause(),
      Select.defaultClause("default_value")
    )
    result shouldBe "default_value"

  it should "return closed when channel is in error" in:
    val ch1 = Channel.newRendezvousChannel[Int]()
    val ex = new RuntimeException("error")
    ch1.error(ex)
    val result = Select.selectOrClosed(ch1.receiveClause())
    result shouldBe a[ChannelError]

  it should "support send clauses" in:
    val ch = Channel.newBufferedChannel[Int](1)
    val result = Select.selectOrClosed(ch.sendClause(42))
    result shouldBe null // sent successfully
    ch.receive() shouldBe 42
