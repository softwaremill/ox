package ox

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

private var customComputeExecutor: ExecutorService = _

/** @see [[oxComputeExecutor]] */
def setOxComputeExecutor(executor: ExecutorService): Unit =
  customComputeExecutor = executor
  if !oxComputeExecutor.eq(customComputeExecutor) then
    throw new RuntimeException("The compute executor was already used before setting a custom one!")

/** The executor which is used to run computations passed to [[computeIntensive]]. By default, a fixed pool of
  * `Runtime.getRuntime.availableProcessors()` daemon platform threads, named `ox-compute-N`, created lazily on first use. Platform threads
  * are preempted by the OS, hence CPU-intensive computations running on this executor can't monopolize the virtual thread scheduler's
  * carrier threads.
  *
  * A custom executor should be set once at the start of the application, before any [[computeIntensive]] calls, using
  * [[setOxComputeExecutor]]; its lifecycle (shutdown) is then the responsibility of the caller.
  *
  * @see
  *   [[OxApp.Settings]]
  */
lazy val oxComputeExecutor: ExecutorService =
  val custom = customComputeExecutor
  if custom == null then
    val counter = new AtomicInteger(0)
    val threadFactory: ThreadFactory = r =>
      val t = new Thread(r, s"ox-compute-${counter.getAndIncrement()}")
      t.setDaemon(true)
      t
    Executors.newFixedThreadPool(Runtime.getRuntime.availableProcessors(), threadFactory)
  else custom
  end if
end oxComputeExecutor

/** Runs `f` on the compute-intensive executor ([[oxComputeExecutor]]), blocking the calling (virtual) thread until it completes. Returns
  * the result of `f`, or rethrows the exception with which it failed.
  *
  * Use for long-running, CPU-intensive computations: virtual threads are not preempted, so run directly in a fork, such a computation would
  * monopolize a carrier thread of the virtual thread scheduler, potentially starving other virtual threads. The computation is instead run
  * on a pool of platform threads (by default sized to the number of available processors), which the OS schedules preemptively. For short
  * computation bursts, which can be instrumented with periodic yields, see [[cede]] as a lighter-weight alternative.
  *
  * As the calling thread blocks until the computation completes, the computation never outlives the enclosing concurrency scope (if any):
  * usage remains structured. To evaluate `f` in parallel with other code, combine with a fork, e.g. `fork(computeIntensive(f))`.
  *
  * If the calling thread is interrupted (e.g. because the enclosing scope ends), the thread running the computation becomes interrupted as
  * well, and the call keeps waiting until the computation completes. The computation can co-operate in the cancellation protocol using
  * [[checkInterrupt]] or [[cede]]. If the computation hasn't yet started when the interruption occurs, it will never run.
  *
  * The scope context is not propagated to the computation: [[ForkLocal]]s read their default values, and forks can't be created within `f`
  * (this fails with an [[IllegalStateException]]).
  *
  * @throws InterruptedException
  *   if the current thread is interrupted, either on entry, or while waiting for the computation to complete.
  */
def computeIntensive[T](f: => T): T = computeIntensive(oxComputeExecutor)(f)

/** As [[computeIntensive]], but runs `f` on the given `executor`, instead of the default [[oxComputeExecutor]]. */
def computeIntensive[T](executor: ExecutorService)(f: => T): T =
  val result = new CompletableFuture[T]()
  executor.execute { () =>
    try result.complete(f).discard
    catch case t: Throwable => result.completeExceptionally(t).discard
  }
  unwrapExecutionException(result.get())
