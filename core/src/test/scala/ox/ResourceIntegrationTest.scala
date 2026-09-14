package ox

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.util.Trail

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class ResourceIntegrationTest extends AnyFlatSpec with Matchers:
  "a concurrency scope" should "provide a resource scope and release resources in LIFO order" in {
    val trail = Trail()

    unsupervised {
      val _ = useInScope { trail.add("allocate 1"); 1 }(n => trail.add(s"release $n"))
      val _ = useInScope { trail.add("allocate 2"); 2 }(n => trail.add(s"release $n"))
      trail.get shouldBe Vector("allocate 1", "allocate 2")
    }

    trail.get shouldBe Vector("allocate 1", "allocate 2", "release 2", "release 1")
  }

  it should "release resources only after child forks finish" in {
    val trail = Trail()
    val started = new CountDownLatch(1)

    supervised {
      releaseAfterScope(trail.add("release"))
      forkDiscard {
        started.countDown()
        try new CountDownLatch(1).await()
        finally trail.add("fork finished")
      }
      started.await()
    }

    trail.get shouldBe Vector("fork finished", "release")
  }

  it should "release all resources and preserve finalizer failure ordering" in {
    val e1 = new RuntimeException("e1")
    val e2 = new RuntimeException("e2")

    val thrown = the[RuntimeException] thrownBy {
      unsupervised {
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
      unsupervised {
        releaseAfterScope(throw e1)
        releaseAfterScope(throw e2)
        throw bodyError
      }
    }

    thrown shouldBe theSameInstanceAs(bodyError)
    thrown.getSuppressed.toSeq shouldBe Seq(e2, e1)
  }

  it should "preserve repeated interruption and leave interrupted status cleared during cleanup" in {
    val cleanupStarted = new CountDownLatch(1)
    val allowCleanup = new CountDownLatch(1)
    val failure = new AtomicReference[Throwable]()
    val interruptedStatus = new AtomicReference[java.lang.Boolean]()

    val usingThread = Thread
      .ofVirtual()
      .start(() =>
        try
          unsupervised {
            releaseAfterScope {
              cleanupStarted.countDown()
              allowCleanup.await()
            }
          }
        catch
          case e: Throwable =>
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
          unsupervised {
            releaseAfterScope(throw outerFinalizerError)
            try
              unsupervised {
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

  "resourceScope" should "attach resources to the nearest scope when nested dynamically in a concurrency scope" in {
    val trail = Trail()
    def inner(): Unit = resourceScope {
      releaseAfterScope(trail.add("inner release"))
      trail.add("inner body")
    }

    supervised {
      releaseAfterScope(trail.add("outer release"))
      inner()
      trail.add("outer body")
    }

    trail.get shouldBe Vector("inner body", "inner release", "outer body", "outer release")
  }

  it should "support a concurrency scope nested inside" in {
    val trail = Trail()

    resourceScope {
      releaseAfterScope(trail.add("outer release"))
      supervised {
        releaseAfterScope(trail.add("inner release"))
        trail.add("inner body")
      }
      trail.add("outer body")
    }

    trail.get shouldBe Vector("inner body", "inner release", "outer body", "outer release")
  }

  it should "reject lexical nesting in a concurrency scope, but allow a capability-free method" in {
    "supervised { resourceScope { } }" shouldNot typeCheck
    "def m()(using Ox): Unit = resourceScope { }" shouldNot typeCheck
    "def m(): Unit = resourceScope { }" should compile
    "resourceScope { resourceScope { } }" should compile
  }

  it should "not allow forking from a resource-only scope" in {
    "resourceScope { forkDiscard { } }" shouldNot typeCheck
  }

  it should "preserve fork-local values in its body and finalizers" in {
    val local = ForkLocal("default")
    val trail = Trail()
    def scoped(): Unit = resourceScope {
      releaseAfterScope(trail.add(s"release ${local.get()}"))
      trail.add(s"body ${local.get()}")
    }

    local.supervisedWhere("modified") { scoped() }

    trail.get shouldBe Vector("body modified", "release modified")
  }

  it should "not expose nested fork-local bindings to finalizers registered through an outer capability" in {
    val local = ForkLocal("default")
    val trail = Trail()

    resourceScope {
      val outer = summon[ResourceScope]
      local.supervisedWhere("modified") {
        releaseAfterScope(trail.add(s"release ${local.get()}"))(using outer)
      }
    }

    trail.get shouldBe Vector("release default")
  }

  "a leaked concurrency-scope capability" should "release an acquired resource immediately and reject registration" in {
    val trail = Trail()
    var leaked: OxUnsupervised = null
    unsupervised { leaked = summon[OxUnsupervised] }

    val e = the[IllegalStateException] thrownBy {
      useInScope { trail.add("allocate"); 1 }(n => trail.add(s"release $n"))(using leaked)
    }

    e.getMessage should include("has already ended")
    trail.get shouldBe Vector("allocate", "release 1")
  }

  private def interruptAndAwaitHandling(thread: Thread): Unit =
    thread.interrupt()
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
    while thread.isInterrupted && System.nanoTime() < deadline do Thread.onSpinWait()
    thread.isInterrupted shouldBe false
    ()
end ResourceIntegrationTest
