package ox.channels.jox

// Ported from: channels/src/test/java/com/softwaremill/jox/SelectTest.java,
//              channels/src/test/java/com/softwaremill/jox/SelectReceiveTest.java,
//              channels/src/test/java/com/softwaremill/jox/SelectSendTest.java (jox 1.1.2)
// Note: Fray/stress tests not ported (require JVM-specific infrastructure)

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class SelectTest extends AnyFlatSpec with Matchers:
  import TestUtil.*

  "selectOrClosed" should "receive from the first available channel" in:
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
    result shouldBe null
    ch.receive() shouldBe 42

  it should "select from a channel with a waiting sender (rendezvous)" in scoped { scope =>
    val ch1 = Channel.newRendezvousChannel[Int]()
    val ch2 = Channel.newRendezvousChannel[Int]()
    forkVoid(scope, () => ch1.send(1))
    Thread.sleep(50)
    val result = Select.selectOrClosed(ch1.receiveClause(), ch2.receiveClause())
    result shouldBe Integer.valueOf(1)
  }

  it should "select a send clause when receiver is waiting" in scoped { scope =>
    val ch = Channel.newRendezvousChannel[Int]()
    val f = fork(scope, () => ch.receive())
    Thread.sleep(50)
    Select.selectOrClosed(ch.sendClause(99))
    f.get() shouldBe 99
  }

  it should "apply transformation callback on receive" in:
    val ch = Channel.newBufferedChannel[Int](1)
    ch.send(10)
    val result = Select.selectOrClosed(ch.receiveClause(v => s"got:$v"))
    result shouldBe "got:10"

  it should "apply callback on send" in:
    val ch = Channel.newBufferedChannel[Int](1)
    val result = Select.selectOrClosed(ch.sendClause(5, () => "sent!"))
    result shouldBe "sent!"
    ch.receive() shouldBe 5

  it should "throw when default clause is not last" in:
    val ch = Channel.newRendezvousChannel[Int]()
    an[IllegalArgumentException] should be thrownBy
      Select.selectOrClosed(Select.defaultClause(0), ch.receiveClause())

  it should "throw when same channel appears in multiple clauses" in:
    val ch = Channel.newBufferedChannel[Int](1)
    an[IllegalArgumentException] should be thrownBy
      Select.selectOrClosed(ch.receiveClause(), ch.receiveClause())
end SelectTest
