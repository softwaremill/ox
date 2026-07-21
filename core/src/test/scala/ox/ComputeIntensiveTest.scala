package ox

import org.scalatest.concurrent.Signaler
import org.scalatest.concurrent.ThreadSignaler
import org.scalatest.concurrent.TimeLimits
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.Seconds
import org.scalatest.time.Span
import ox.either.ok

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.*

class ComputeIntensiveTest extends AnyFlatSpec with Matchers with TimeLimits:
  "computeIntensive" should "return the result of the computation" in {
    computeIntensive(2 + 2) shouldBe 4
  }

  it should "run the computation on a platform thread from the default compute pool" in {
    val (name, isVirtual) = computeIntensive((Thread.currentThread().getName, Thread.currentThread().isVirtual))
    name should startWith("ox-compute-")
    isVirtual shouldBe false
  }

  it should "rethrow exceptions unchanged" in {
    val e = new RuntimeException("boom")
    val thrown = intercept[RuntimeException](computeIntensive(throw e))
    (thrown eq e) shouldBe true
  }

  it should "throw when setting a custom executor after the default one has been used" in {
    computeIntensive(1) shouldBe 1 // forces initialization of the default executor
    val executor = java.util.concurrent.Executors.newFixedThreadPool(1)
    try intercept[RuntimeException](setOxComputeExecutor(executor)).discard
    finally executor.shutdownNow().discard
  }

  it should "throw immediately when the caller is already interrupted, without running the computation" in {
    val ran = new AtomicBoolean(false)
    Thread.currentThread().interrupt()
    try intercept[InterruptedException](computeIntensive { ran.set(true) }).discard
    finally Thread.interrupted().discard // clear the flag defensively, should the assertion fail
    ran.get() shouldBe false
  }

  it should "interrupt the computation and await its completion when the caller is interrupted" in {
    val started = new CountDownLatch(1)
    val cleanupDone = new AtomicBoolean(false)
    supervised {
      val f = forkCancellable {
        computeIntensive {
          started.countDown()
          try Thread.sleep(10_000)
          catch
            case e: InterruptedException =>
              Thread.sleep(100) // post-interrupt cleanup, which must complete before the caller returns
              cleanupDone.set(true)
              throw e
        }
      }
      started.await()
      f.cancel() should matchPattern { case Left(_: InterruptedException) => }
      // proves the caller waited for the computation (including its cleanup) to complete
      cleanupDone.get() shouldBe true
    }
  }

  it should "never run the computation if it is cancelled before it started" in {
    val executor = Executors.newFixedThreadPool(1)
    try
      val blockerStarted = new CountDownLatch(1)
      val releaseBlocker = new CountDownLatch(1)
      val secondRan = new AtomicBoolean(false)
      supervised {
        forkDiscard(computeIntensive(executor) { blockerStarted.countDown(); releaseBlocker.await() })
        blockerStarted.await()
        val f2 = forkCancellable(computeIntensive(executor) { secondRan.set(true) })
        sleep(100.millis) // give the second task time to be submitted & queued behind the blocker
        f2.cancel() should matchPattern { case Left(_: InterruptedException) => }
        releaseBlocker.countDown()
      }
      // even after the blocker completes, the cancelled task must not run
      executor.shutdown()
      executor.awaitTermination(5, TimeUnit.SECONDS) shouldBe true
      secondRan.get() shouldBe false
    finally executor.shutdownNow().discard
    end try
  }

  it should "rethrow an InterruptedException thrown by the computation itself" in {
    val e = new InterruptedException("from computation")
    val thrown = intercept[InterruptedException](computeIntensive(throw e))
    (thrown eq e) shouldBe true
    Thread.currentThread().isInterrupted shouldBe false // the caller was not interrupted
  }

  it should "run a nested computation targeting the same executor inline, on the same thread" in {
    val (outer, inner) = computeIntensive((Thread.currentThread(), computeIntensive(Thread.currentThread())))
    (inner eq outer) shouldBe true
  }

  it should "not deadlock nested computations, even on a single-threaded executor" in {
    val executor = Executors.newSingleThreadExecutor()
    try computeIntensive(executor)(computeIntensive(executor)(42)) shouldBe 42
    finally executor.shutdownNow().discard
  }

  it should "run a nested computation targeting a different executor on that executor" in {
    val counter = new AtomicInteger(0)
    val custom = Executors.newFixedThreadPool(1, r => new Thread(r, s"custom-compute-${counter.getAndIncrement()}"))
    try
      val innerThreadName = computeIntensive(computeIntensive(custom)(Thread.currentThread().getName))
      innerThreadName should startWith("custom-compute-")
    finally custom.shutdownNow().discard
  }

  it should "not propagate fork locals to the computation" in {
    val l = ForkLocal("default")
    l.supervisedWhere("changed") {
      computeIntensive(l.get()) shouldBe "default"
    }
  }

  it should "not allow forking from within the computation" in {
    supervised {
      intercept[IllegalStateException](computeIntensive(fork(1).discard)).discard
    }
  }

  it should "work within either blocks" in {
    val result = either {
      computeIntensive {
        val e: Either[String, Int] = Left("boom")
        e.ok()
      }
    }
    result shouldBe Left("boom")
  }

  it should "not starve virtual threads when the compute pool is saturated" in {
    implicit val signaler: Signaler = ThreadSignaler // a platform timer thread, fires even if virtual-thread carriers are starved
    failAfter(Span(30, Seconds)) {
      val stop = new AtomicBoolean(false)
      val completed = new AtomicInteger(0)
      supervised {
        for _ <- 1 to Runtime.getRuntime.availableProcessors() do
          forkDiscard(computeIntensive {
            while !stop.get() && !Thread.currentThread().isInterrupted do () // busy-spin, hogging a compute-pool thread
          })
        val vts = (1 to 100).map(_ => fork { sleep(10.millis); completed.incrementAndGet() })
        vts.foreach(_.join().discard)
        completed.get() shouldBe 100
        stop.set(true)
      }
    }
  }
end ComputeIntensiveTest
