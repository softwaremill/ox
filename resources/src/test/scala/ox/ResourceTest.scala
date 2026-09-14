package ox

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.util.Trail

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class ResourceTest extends AnyFlatSpec with Matchers:
  "resourceScope" should "release resources in LIFO order" in {
    val trail = Trail()

    resourceScope {
      val first = useInScope { trail.add("allocate 1"); 1 }(n => trail.add(s"release $n"))
      val second = useInScope { trail.add("allocate 2"); 2 }(n => trail.add(s"release $n"))
      first shouldBe 1
      second shouldBe 2
      trail.get shouldBe Vector("allocate 1", "allocate 2")
    }

    trail.get shouldBe Vector("allocate 1", "allocate 2", "release 2", "release 1")
  }

  it should "release all resources and preserve finalizer failure ordering" in {
    val e1 = new RuntimeException("e1")
    val e2 = new RuntimeException("e2")

    val thrown = the[RuntimeException] thrownBy {
      resourceScope {
        releaseAfterScope(throw e1)
        releaseAfterScope(throw e2)
      }
    }

    thrown shouldBe theSameInstanceAs(e2)
    thrown.getSuppressed.toSeq shouldBe Seq(e1)
  }

  it should "keep the body failure primary and suppress finalizer failures in execution order" in {
    val bodyError = new RuntimeException("body")
    val e1 = new RuntimeException("e1")
    val e2 = new RuntimeException("e2")

    val thrown = the[RuntimeException] thrownBy {
      resourceScope {
        releaseAfterScope(throw e1)
        releaseAfterScope(throw e2)
        throw bodyError
      }
    }

    thrown shouldBe theSameInstanceAs(bodyError)
    thrown.getSuppressed.toSeq shouldBe Seq(e2, e1)
  }

  it should "release nested scopes independently" in {
    val trail = Trail()

    resourceScope {
      releaseAfterScope(trail.add("outer release"))
      resourceScope {
        releaseAfterScope(trail.add("inner release"))
        trail.add("inner body")
      }
      trail.add("outer body")
    }

    trail.get shouldBe Vector("inner body", "inner release", "outer body", "outer release")
  }

  it should "run closeable and release-only helpers" in {
    val trail = Trail()
    class Closeable(name: String) extends AutoCloseable:
      override def close(): Unit = trail.add(s"close $name")

    resourceScope {
      val _ = useCloseableInScope(new Closeable("acquired"))
      releaseCloseableAfterScope(new Closeable("registered"))
      releaseAfterScope(trail.add("release block"))
    }

    trail.get shouldBe Vector("release block", "close registered", "close acquired")
  }

  it should "reject a leaked capability and immediately clean up an acquired resource" in {
    val trail = Trail()
    var leaked: ResourceScope = null
    resourceScope { leaked = summon[ResourceScope] }

    val thrown = the[IllegalStateException] thrownBy {
      useInScope { trail.add("allocate"); 1 }(n => trail.add(s"release $n"))(using leaked)
    }

    thrown.getMessage should include("has already ended")
    trail.get shouldBe Vector("allocate", "release 1")
  }

  it should "wait for finalization before rethrowing interruption" in {
    val cleanupStarted = new CountDownLatch(1)
    val allowCleanup = new CountDownLatch(1)
    val trail = Trail()
    val failure = new AtomicReference[Throwable]()
    val interruptedStatus = new AtomicReference[java.lang.Boolean]()

    val usingThread = Thread
      .ofVirtual()
      .start(() =>
        try
          resourceScope {
            releaseAfterScope {
              cleanupStarted.countDown()
              allowCleanup.await()
              trail.add("cleanup")
            }
          }
        catch
          case e: Throwable =>
            trail.add("interrupted")
            interruptedStatus.set(Thread.currentThread().isInterrupted)
            failure.set(e)
      )

    cleanupStarted.await(10, TimeUnit.SECONDS) shouldBe true
    interruptAndAwaitHandling(usingThread)
    interruptAndAwaitHandling(usingThread)

    allowCleanup.countDown()
    usingThread.join()

    failure.get() shouldBe a[InterruptedException]
    failure.get().getSuppressed.toSeq should have size 1
    failure.get().getSuppressed.head shouldBe a[InterruptedException]
    interruptedStatus.get() shouldBe false
    trail.get shouldBe Vector("cleanup", "interrupted")
  }

  it should "keep an inner interruption primary and suppress outer finalizer failures" in {
    val innerCleanupStarted = new CountDownLatch(1)
    val allowInnerCleanup = new CountDownLatch(1)
    val outerFinalizerError = new RuntimeException("outer finalizer")
    val innerFailure = new AtomicReference[InterruptedException]()
    val failure = new AtomicReference[Throwable]()
    val interruptedStatus = new AtomicReference[java.lang.Boolean]()

    val usingThread = Thread
      .ofVirtual()
      .start(() =>
        try
          resourceScope {
            releaseAfterScope(throw outerFinalizerError)
            try
              resourceScope {
                releaseAfterScope {
                  innerCleanupStarted.countDown()
                  allowInnerCleanup.await()
                }
              }
            catch
              case e: InterruptedException =>
                innerFailure.set(e)
                throw e
            end try
          }
        catch
          case e: Throwable =>
            interruptedStatus.set(Thread.currentThread().isInterrupted)
            failure.set(e)
      )

    innerCleanupStarted.await(10, TimeUnit.SECONDS) shouldBe true
    interruptAndAwaitHandling(usingThread)
    allowInnerCleanup.countDown()
    usingThread.join()

    innerFailure.get() should not be null
    failure.get() shouldBe theSameInstanceAs(innerFailure.get())
    failure.get().getSuppressed.toSeq shouldBe Seq(outerFinalizerError)
    interruptedStatus.get() shouldBe false
  }

  "standalone use helpers" should "release resources and suppress release failures" in {
    val trail = Trail()
    val bodyError = new RuntimeException("body")
    val releaseError = new RuntimeException("release")

    val thrown = the[RuntimeException] thrownBy {
      use(
        { trail.add("allocate"); 1 },
        _ =>
          trail.add("release"); throw releaseError
      ) { _ =>
        trail.add("body")
        throw bodyError
      }
    }

    thrown shouldBe theSameInstanceAs(bodyError)
    thrown.getSuppressed.toSeq shouldBe Seq(releaseError)
    trail.get shouldBe Vector("allocate", "body", "release")
  }

  it should "support interruptible release and AutoCloseable" in {
    val trail = Trail()
    class Closeable extends AutoCloseable:
      override def close(): Unit = trail.add("close")

    useInterruptible({ trail.add("allocate"); 1 }, n => trail.add(s"release $n"))(n => trail.add(s"use $n"))
    useCloseable(new Closeable)(_ => trail.add("use closeable"))

    trail.get shouldBe Vector("allocate", "use 1", "release 1", "use closeable", "close")
  }

  private def interruptAndAwaitHandling(thread: Thread): Unit =
    thread.interrupt()
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
    while thread.isInterrupted && System.nanoTime() < deadline do Thread.onSpinWait()
    thread.isInterrupted shouldBe false
    ()
end ResourceTest
