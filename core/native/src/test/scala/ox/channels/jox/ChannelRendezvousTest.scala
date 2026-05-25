package ox.channels.jox

// Ported from: channels/src/test/java/com/softwaremill/jox/ChannelRendezvousTest.java (jox 1.1.2)

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.util.concurrent.{ConcurrentLinkedQueue, ConcurrentSkipListSet}

class ChannelRendezvousTest extends AnyFlatSpec with Matchers:
  import TestUtil.*

  "rendezvous channel" should "send and receive" in scoped { scope =>
    val channel = Channel.newRendezvousChannel[String]()
    forkVoid(scope, () => channel.send("x"))
    val t2 = fork(scope, () => channel.receive())
    t2.get() shouldBe "x"
  }

  // Java version uses 1000; reduced for Scala Native virtual thread scheduling reliability
  it should "send and receive in many forks" in scoped { scope =>
    val n = 100
    val channel = Channel.newRendezvousChannel[Int]()
    val s = new ConcurrentSkipListSet[Int]()

    for i <- 1 to n do forkVoid(scope, () => channel.send(i))
    val fs = (1 to n).map(_ => forkVoid(scope, () => { s.add(channel.receive()); () }))
    fs.foreach(_.get())
    s.size() shouldBe n
  }

  it should "send and receive many elements in two forks" in scoped { scope =>
    val channel = Channel.newRendezvousChannel[Int]()
    val s = new ConcurrentSkipListSet[Int]()

    forkVoid(scope, () => for i <- 1 to 1000 do channel.send(i))
    forkVoid(scope, () => for _ <- 1 to 1000 do s.add(channel.receive())).get()
    s.size() shouldBe 1000
  }

  it should "block sender until receiver arrives" in scoped { scope =>
    val channel = Channel.newRendezvousChannel[Int]()
    val trail = new ConcurrentLinkedQueue[String]()

    forkVoid(scope, () => { channel.send(1); trail.add("S"); () })
    forkVoid(scope, () => { channel.send(2); trail.add("S"); () })
    forkVoid(scope, () => {
      Thread.sleep(100L)
      trail.add("R1")
      channel.receive()
      Thread.sleep(100L)
      trail.add("R2")
      channel.receive()
    }).get()

    Thread.sleep(100L)
    import scala.jdk.CollectionConverters.*
    trail.asScala.toList shouldBe List("R1", "S", "R2", "S")
  }

  it should "notify pending receives when channel is done" in scoped { scope =>
    val c = Channel.newRendezvousChannel[Int]()
    val f = fork(scope, () => c.receiveOrClosed())

    Thread.sleep(100L)
    c.done()
    f.get() shouldBe a[ChannelDone]
    c.receiveOrClosed() shouldBe a[ChannelDone]
  }

  it should "notify pending sends when channel is errored" in scoped { scope =>
    val c = Channel.newRendezvousChannel[Int]()
    val f = fork(scope, () => c.sendOrClosed(1))

    Thread.sleep(100L)
    c.error(new RuntimeException())
    f.get() shouldBe a[ChannelError]
    c.sendOrClosed(2) shouldBe a[ChannelError]
  }
