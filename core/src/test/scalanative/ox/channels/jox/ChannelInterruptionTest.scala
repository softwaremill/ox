package ox.channels.jox

// Ported from: channels/src/test/java/com/softwaremill/jox/ChannelInterruptionTest.java (jox 1.1.2)
// Note: Memory leak tests and Fray tests not ported (require JVM-specific tooling)

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ChannelInterruptionTest extends AnyFlatSpec with Matchers:
  import TestUtil.*

  "interruption" should "interrupt a blocked send on rendezvous channel" in scoped { scope =>
    val ch = Channel.newRendezvousChannel[Int]()
    val f = forkCancelable(scope, () => ch.send(1))
    Thread.sleep(50)
    val result = f.cancel()
    result shouldBe a[InterruptedException]
  }

  it should "interrupt a blocked receive on rendezvous channel" in scoped { scope =>
    val ch = Channel.newRendezvousChannel[Int]()
    val f = forkCancelable(scope, () => ch.receive())
    Thread.sleep(50)
    val result = f.cancel()
    result shouldBe a[InterruptedException]
  }

  it should "interrupt a blocked send on full buffered channel" in scoped { scope =>
    val ch = Channel.newBufferedChannel[Int](1)
    ch.send(1) // fill buffer
    val f = forkCancelable(scope, () => ch.send(2))
    Thread.sleep(50)
    val result = f.cancel()
    result shouldBe a[InterruptedException]
  }

  it should "interrupt a blocked receive on empty buffered channel" in scoped { scope =>
    val ch = Channel.newBufferedChannel[Int](1)
    val f = forkCancelable(scope, () => ch.receive())
    Thread.sleep(50)
    val result = f.cancel()
    result shouldBe a[InterruptedException]
  }

  it should "allow channel to continue working after interrupted send" in scoped { scope =>
    val ch = Channel.newRendezvousChannel[Int]()

    // start a sender, then interrupt it
    val f = forkCancelable(scope, () => ch.send(1))
    Thread.sleep(50)
    f.cancel()

    // channel should still work
    forkVoid(scope, () => ch.send(2))
    Thread.sleep(50)
    ch.receive() shouldBe 2
  }

  it should "allow channel to continue working after interrupted receive" in scoped { scope =>
    val ch = Channel.newRendezvousChannel[Int]()

    // start a receiver, then interrupt it
    val f = forkCancelable(scope, () => ch.receive())
    Thread.sleep(50)
    f.cancel()

    // channel should still work
    forkVoid(scope, () => ch.send(3))
    Thread.sleep(50)
    ch.receive() shouldBe 3
  }
end ChannelInterruptionTest
