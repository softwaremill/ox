package ox.channels.jox

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ChannelRendezvousTest extends AnyFlatSpec with Matchers:
  "rendezvous channel" should "send and receive" in:
    val ch = Channel.newRendezvousChannel[String]()
    scoped {
      fork {
        ch.send("hello")
      }
      ch.receive() shouldBe "hello"
    }

  it should "send and receive multiple values" in:
    val ch = Channel.newRendezvousChannel[Int]()
    scoped {
      fork {
        for i <- 1 to 5 do ch.send(i)
      }
      for i <- 1 to 5 do ch.receive() shouldBe i
    }

  it should "block sender until receiver arrives" in:
    val ch = Channel.newRendezvousChannel[Int]()
    @volatile var sent = false
    scoped {
      fork {
        ch.send(42)
        sent = true
      }
      Thread.sleep(50)
      sent shouldBe false
      ch.receive() shouldBe 42
      Thread.sleep(50)
      sent shouldBe true
    }

  it should "handle done" in:
    val ch = Channel.newRendezvousChannel[Int]()
    ch.done()
    ch.receiveOrClosed() shouldBe a[ChannelDone]

  it should "handle error" in:
    val ch = Channel.newRendezvousChannel[Int]()
    val ex = new RuntimeException("test")
    ch.error(ex)
    ch.receiveOrClosed() match
      case e: ChannelError => e.cause shouldBe ex
      case other           => fail(s"Expected ChannelError, got $other")

  private def scoped(f: => Unit): Unit = f

  private def fork(f: => Unit): Thread =
    val t = Thread.ofVirtual().start(() => f)
    t
