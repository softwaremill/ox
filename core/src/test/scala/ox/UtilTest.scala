package ox

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.util.Trail
import ox.*

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class UtilTest extends AnyFlatSpec with Matchers:
  "discard" should "do nothing" in {
    val t = Trail()
    def f(): Int =
      t.add("in f")
      42

    f().discard shouldBe ()
    t.get shouldBe Vector("in f")
  }

  "tapException" should "run the callback when an exception is thrown" in {
    val t = Trail()
    def f(): Int = throw new RuntimeException("boom!")

    try f().tapException(e => t.add(s"in callback: ${e.getMessage}"))
    catch case e: RuntimeException => t.add(s"in catch: ${e.getMessage}")

    t.get shouldBe Vector("in callback: boom!", "in catch: boom!")
  }

  it should "not run the callback when no exception is thrown" in {
    val t = Trail()
    def f(): Int = 42

    try
      t.add(f().tapException(e => t.add(s"in callback: ${e.getMessage}")).toString)
      t.add("after")
    catch case e: RuntimeException => t.add(s"in catch: ${e.getMessage}")

    t.get shouldBe Vector("42", "after")
  }

  it should "suppress any additional exceptions" in {
    val t = Trail()

    def f(): Int = throw new RuntimeException("boom!")

    try f().tapException(_ => throw new RuntimeException("boom boom!"))
    catch case e: RuntimeException => t.add(s"in catch: ${e.getMessage} ${e.getSuppressed.length}")

    t.get shouldBe Vector("in catch: boom! 1")
  }

  "pipe" should "work" in {
    (1 + 2).pipe(_ * 2) shouldBe 6
  }

  "tap" should "work" in {
    val t = Trail()
    {
      t.add("Adding")
      1 + 2
    }.tap(v => t.add(s"Got: $v")) shouldBe 3
    t.get shouldBe Vector("Adding", "Got: 3")
  }

  "debug as extension" should "work" in {
    val x = 10
    x.debug("some label") shouldBe 10
  }

  "debug as top-level method" should "work" in {
    val x = 10
    debug(x + 1) shouldBe ()
  }

  "uninterruptible" should "complete the body and then rethrow an interruption received while waiting" in {
    val trail = Trail()
    val (waiting, allowBody, failure) = startUninterruptible {
      trail.add("body completed")
    }

    interruptAndAwaitHandling(waiting)
    allowBody.countDown()
    waiting.join()

    trail.get shouldBe Vector("body completed")
    failure.get() shouldBe a[InterruptedException]
  }

  it should "rethrow the body's failure, when the body fails after an interruption was received while waiting" in {
    val bodyError = new RuntimeException("body")
    val (waiting, allowBody, failure) = startUninterruptible {
      throw bodyError
    }

    interruptAndAwaitHandling(waiting)
    allowBody.countDown()
    waiting.join()

    failure.get() shouldBe theSameInstanceAs(bodyError)
  }

  it should "rethrow an InterruptedException thrown by its body" in {
    val bodyError = new InterruptedException("thrown by the body")

    val thrown = the[InterruptedException] thrownBy uninterruptible[Unit](throw bodyError)

    thrown shouldBe theSameInstanceAs(bodyError)
  }

  it should "rethrow an InterruptedException thrown when releasing a resource" in {
    val releaseError = new InterruptedException("thrown by the release")

    val thrown = the[InterruptedException] thrownBy use((), _ => throw releaseError)(_ => ())

    thrown shouldBe theSameInstanceAs(releaseError)
  }

  /** Starts `uninterruptible(body)` on a new thread, with `body` blocked until the returned latch is released. Returns once the body is
    * running.
    */
  private def startUninterruptible(body: => Unit): (Thread, CountDownLatch, AtomicReference[Throwable]) =
    val bodyStarted = new CountDownLatch(1)
    val allowBody = new CountDownLatch(1)
    val failure = new AtomicReference[Throwable]()

    val waiting = Thread
      .ofVirtual()
      .start(() =>
        try
          uninterruptible {
            bodyStarted.countDown()
            allowBody.await()
            body
          }
        catch case e: Throwable => failure.set(e)
      )

    bodyStarted.await(10, TimeUnit.SECONDS) shouldBe true
    (waiting, allowBody, failure)
  end startUninterruptible

  private def interruptAndAwaitHandling(thread: Thread): Unit =
    thread.interrupt()
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
    while thread.isInterrupted && System.nanoTime() < deadline do Thread.onSpinWait()
    thread.isInterrupted shouldBe false
    ()
end UtilTest
