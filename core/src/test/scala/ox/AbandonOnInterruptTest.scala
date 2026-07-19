package ox

import org.scalatest.concurrent.Eventually
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Millis, Seconds, Span}
import ox.*
import ox.util.Trail

import java.io.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import scala.concurrent.duration.*

class AbandonOnInterruptTest extends AnyFlatSpec with Matchers with Eventually:
  override implicit val patienceConfig: PatienceConfig = PatienceConfig(timeout = Span(5, Seconds), interval = Span(10, Millis))

  "abandonOnInterrupt" should "return the operation's result" in {
    abandonOnInterrupt(42) shouldBe 42
  }

  it should "propagate the operation's exception" in {
    the[RuntimeException] thrownBy abandonOnInterrupt(throw new RuntimeException("boom")) should have message "boom"
  }

  it should "abandon the operation on interrupt, letting it complete on the detached thread" in {
    val release = CountDownLatch(1)
    val opCompleted = CountDownLatch(1)

    supervised {
      timeoutOption(100.millis) {
        abandonOnInterrupt {
          uninterruptible(release.await())
          opCompleted.countDown()
        }
      } shouldBe None
    }
    opCompleted.getCount shouldBe 1 // op still blocked, even though the scope completed
    release.countDown()
    opCompleted.await() // op completes on the detached thread after being released
  }

  it should "run onAbandon when interrupted, on a separate thread" in {
    val release = CountDownLatch(1)
    val abandoned = CountDownLatch(1)

    supervised {
      timeoutOption(100.millis) {
        abandonOnInterrupt(uninterruptible(release.await()))(abandoned.countDown())
      } shouldBe None
    }
    abandoned.await() // onAbandon ran
    release.countDown()
  }

  it should "not run onAbandon when the operation succeeds or fails" in {
    val abandoned = AtomicBoolean(false)

    abandonOnInterrupt(42)(abandoned.set(true)) shouldBe 42
    the[RuntimeException] thrownBy abandonOnInterrupt(throw new RuntimeException("boom"))(abandoned.set(true)) should
      have message "boom"
    abandoned.get() shouldBe false
  }

  it should "treat an InterruptedException thrown by the operation as a failure, not abandonment" in {
    val abandoned = AtomicBoolean(false)
    an[InterruptedException] shouldBe thrownBy(abandonOnInterrupt(throw new InterruptedException("from op"))(abandoned.set(true)))
    Thread.interrupted() shouldBe false // the caller's interrupt flag wasn't set
    abandoned.get() shouldBe false
  }

  // read() blocks (uninterruptibly) until released, then serves `data` bytes, then EOF
  private class BlockingInputStream(release: CountDownLatch, data: Array[Byte]) extends InputStream:
    val closed = AtomicBoolean(false)
    private var pos = 0
    override def read(): Int =
      uninterruptible(release.await())
      if pos < data.length then { val b = data(pos) & 0xff; pos += 1; b }
      else -1
    override def close(): Unit = closed.set(true)

  "abandonOnInterruptReads" should "read the content through, in chunks" in {
    val data = Array.tabulate[Byte](100)(_.toByte)
    val wrapped = abandonOnInterruptReads(ByteArrayInputStream(data), chunkSize = 7)
    wrapped.readAllBytes() shouldBe data
    wrapped.read() shouldBe -1
  }

  it should "propagate an underlying exception" in {
    val failing = new InputStream:
      override def read(): Int = throw new IOException("disk on fire")
    val wrapped = abandonOnInterruptReads(failing)
    the[IOException] thrownBy wrapped.read() should have message "disk on fire"
    the[IOException] thrownBy wrapped.read() should have message "disk on fire" // error is persistent
  }

  it should "abandon an interrupted read without losing the in-flight chunk" in {
    val release = CountDownLatch(1)
    val wrapped = abandonOnInterruptReads(BlockingInputStream(release, Array[Byte](42)), chunkSize = 1)

    supervised {
      timeoutOption(100.millis)(wrapped.read()) shouldBe None // interrupted: abandoned
      release.countDown()
      wrapped.read() shouldBe 42 // the chunk read after abandonment is served to the next read
      wrapped.read() shouldBe -1
    }
  }

  it should "close the underlying stream on abandonment when closeOnAbandon is set" in {
    val release = CountDownLatch(1)
    val underlying = BlockingInputStream(release, Array[Byte](42))
    val wrapped = abandonOnInterruptReads(underlying, closeOnAbandon = true)

    supervised {
      timeoutOption(100.millis)(wrapped.read()) shouldBe None
    }
    release.countDown()
    eventually(underlying.closed.get() shouldBe true) // the close is fire-and-forget on a detached thread
    an[IOException] shouldBe thrownBy(wrapped.read()) // the wrapper is permanently closed
  }

  it should "close the underlying stream when the wrapper is closed" in {
    val underlying = ByteArrayInputStream(Array[Byte](1, 2, 3))
    val closed = AtomicBoolean(false)
    val tracking = new FilterInputStream(underlying):
      override def close(): Unit = { closed.set(true); super.close() }
    val wrapped = abandonOnInterruptReads(tracking)
    wrapped.read() shouldBe 1
    wrapped.close()
    closed.get() shouldBe true
    an[IOException] shouldBe thrownBy(wrapped.read())
  }

  it should "report available bytes from the current chunk" in {
    val wrapped = abandonOnInterruptReads(ByteArrayInputStream(Array[Byte](1, 2, 3)), chunkSize = 3)
    wrapped.available() shouldBe 0 // no chunk received yet
    wrapped.read() shouldBe 1
    wrapped.available() shouldBe 2
    wrapped.close()
    wrapped.available() shouldBe 0
  }

  // write() blocks (uninterruptibly) until released; records writes and close
  private class BlockingOutputStream(release: CountDownLatch) extends OutputStream:
    val closed = AtomicBoolean(false)
    val written = java.util.concurrent.ConcurrentLinkedQueue[Int]()
    override def write(b: Int): Unit =
      uninterruptible(release.await())
      written.add(b).discard
    override def close(): Unit = closed.set(true)

  "abandonOnInterruptWrites" should "write the content through" in {
    val underlying = ByteArrayOutputStream()
    val wrapped = abandonOnInterruptWrites(underlying)
    wrapped.write(Array[Byte](1, 2, 3))
    wrapped.write(4)
    wrapped.flush()
    underlying.toByteArray shouldBe Array[Byte](1, 2, 3, 4)
    wrapped.close()
  }

  it should "close the underlying stream after completing queued writes" in {
    val underlying = ByteArrayOutputStream()
    val closed = AtomicBoolean(false)
    val tracking = new FilterOutputStream(underlying):
      override def close(): Unit = { closed.set(true); super.close() }
      override def write(b: Array[Byte], off: Int, len: Int): Unit =
        // rejecting out-of-order writes, so that a close happening before draining queued writes is observable
        if closed.get() then throw new IOException("write after close")
        underlying.write(b, off, len)
    val wrapped = abandonOnInterruptWrites(tracking)
    wrapped.write(Array[Byte](1, 2, 3))
    wrapped.close()
    closed.get() shouldBe true
    underlying.toByteArray shouldBe Array[Byte](1, 2, 3)
    an[IOException] shouldBe thrownBy(wrapped.write(4))
  }

  it should "surface a pending write error on close" in {
    val failing = new OutputStream:
      override def write(b: Int): Unit = throw new IOException("pipe broken")
    val wrapped = abandonOnInterruptWrites(failing)
    wrapped.write(42) // enqueued; fails asynchronously
    an[IOException] shouldBe thrownBy(wrapped.close())
  }

  it should "abandon a flush blocked awaiting completion on interrupt" in {
    val release = CountDownLatch(1)
    val flushed = AtomicBoolean(false)
    val underlying = new OutputStream:
      override def write(b: Int): Unit = ()
      override def flush(): Unit = { uninterruptible(release.await()); flushed.set(true) }
    val wrapped = abandonOnInterruptWrites(underlying)

    supervised {
      // the Flush command is delivered promptly (the detached thread is idle), so the caller blocks awaiting the
      // ack, while the detached thread blocks in the underlying flush — this exercises the ack-await abandon path
      timeoutOption(100.millis)(wrapped.flush()) shouldBe None
    }
    release.countDown()
    eventually(flushed.get() shouldBe true)
  }

  it should "close the underlying stream once when close is abandoned while awaiting completion (closeOnAbandon)" in {
    val release = CountDownLatch(1)
    val closeCount = AtomicInteger(0)
    val underlying = new OutputStream:
      override def write(b: Int): Unit = ()
      override def close(): Unit = { uninterruptible(release.await()); closeCount.incrementAndGet().discard }
    val wrapped = abandonOnInterruptWrites(underlying, closeOnAbandon = true)

    supervised {
      // the Close command is delivered; the caller is interrupted while awaiting the ack — the abandonment must not
      // start a second, concurrent close
      timeoutOption(100.millis)(wrapped.close()) shouldBe None
    }
    release.countDown()
    // both the delivered Close and any (buggy) second close would complete shortly after the release
    Thread.sleep(200)
    closeCount.get() shouldBe 1
  }

  it should "surface an asynchronous write error on the next operation" in {
    val failing = new OutputStream:
      override def write(b: Int): Unit = throw new IOException("pipe broken")
    val wrapped = abandonOnInterruptWrites(failing)
    wrapped.write(42) // enqueued; the failure happens asynchronously
    an[IOException] shouldBe thrownBy(wrapped.flush())
    an[IOException] shouldBe thrownBy(wrapped.write(43)) // the error is persistent
  }

  it should "abandon a blocked write on interrupt" in {
    val release = CountDownLatch(1)
    val underlying = BlockingOutputStream(release)
    val wrapped = abandonOnInterruptWrites(underlying)

    supervised {
      wrapped.write(1) // handed to the detached thread, which blocks in the underlying write
      // the rendezvous channel now backpressures: this write blocks, but interruptibly
      timeoutOption(100.millis)(wrapped.write(2)) shouldBe None
    }
    release.countDown()
    eventually(underlying.written.contains(1) shouldBe true)
  }

  it should "close the underlying stream on abandonment when closeOnAbandon is set" in {
    val release = CountDownLatch(1)
    val underlying = BlockingOutputStream(release)
    val wrapped = abandonOnInterruptWrites(underlying, closeOnAbandon = true)

    supervised {
      wrapped.write(1)
      timeoutOption(100.millis)(wrapped.write(2)) shouldBe None
    }
    release.countDown()
    eventually(underlying.closed.get() shouldBe true)
    an[IOException] shouldBe thrownBy(wrapped.write(3))
  }

  "abandonOnInterruptReads" should "let a scope cancel a fork blocked on an uninterruptible read" in {
    val release = CountDownLatch(1)
    val underlying = BlockingInputStream(release, Array[Byte](42))
    val wrapped = abandonOnInterruptReads(underlying, closeOnAbandon = true)
    val trail = Trail()

    supervised {
      forkDiscard {
        try wrapped.read().discard
        catch
          case e: InterruptedException =>
            trail.add("fork interrupted")
            throw e
      }
      sleep(100.millis) // let the fork block in the (wrapped, interruptible) read
      trail.add("body done")
    } // scope end cancels the fork promptly — this must not hang
    trail.get shouldBe Vector("body done", "fork interrupted")
    release.countDown()
    eventually(underlying.closed.get() shouldBe true)
  }

end AbandonOnInterruptTest
