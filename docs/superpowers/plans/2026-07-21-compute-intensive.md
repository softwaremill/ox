# `computeIntensive` Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add `computeIntensive`, which runs a CPU-intensive computation on a pool of OS-preemptible platform threads while the calling (virtual) thread blocks awaiting the result, per the approved spec at `docs/superpowers/specs/2026-07-21-compute-intensive-design.md`.

**Architecture:** A new top-level function in `core`, backed by a lazily-created global fixed pool of daemon platform threads (size = available processors), replaceable via `setOxComputeExecutor` (mirroring `setOxThreadFactory`) or a per-call `ExecutorService` overload. Interruption of the caller interrupts the pool task and then awaits its completion (strict structure). Nested calls targeting the same executor run inline (fixed-pool deadlock avoidance).

**Tech Stack:** Scala 3 (this repo targets 3.3.x), JDK 21+, sbt, ScalaTest (AnyFlatSpec + Matchers).

## Global Constraints

- All code in the `core` module; no new dependencies.
- Follow the repo's existing patterns: `setOxThreadFactory`/`oxThreadFactory` for global config (`core/src/main/scala/ox/oxThreadFactory.scala`), binary-compatibility overloads for `OxApp.Settings` (see the "required for binary compatibility" comments in `OxApp.scala`).
- Scaladoc style: `/** ... */` with `@throws`/`@see` tags, as in `control.scala` / `fork.scala`.
- Format with scalafmt before each commit: `sbt "core/scalafmtAll"` (config: `.scalafmt.conf`, scalafmt 3.11.4).
- Test command pattern: `sbt "core/testOnly ox.ComputeIntensiveTest"`. Tests use `AnyFlatSpec with Matchers` (see `core/src/test/scala/ox/LocalTest.scala` for style).
- `unwrapExecutionException` and `discard` are existing `private[ox]`/public helpers — use them, don't reimplement.
- Do NOT write a test that calls `setOxComputeExecutor` successfully (it would poison the JVM-global default for other suites); only the "throws after first use" path is tested.

---

### Task 1: Default pool, `setOxComputeExecutor`, basic `computeIntensive`

**Files:**
- Create: `core/src/main/scala/ox/computeIntensive.scala`
- Create (test): `core/src/test/scala/ox/ComputeIntensiveTest.scala`

**Interfaces:**
- Consumes: `ox.discard` (util.scala), `ox.unwrapExecutionException` (fork.scala, `private[ox]`).
- Produces: `def computeIntensive[T](f: => T): T`, `def computeIntensive[T](executor: ExecutorService)(f: => T): T`, `def setOxComputeExecutor(executor: ExecutorService): Unit`, `lazy val oxComputeExecutor: ExecutorService` — all in package `ox`. Later tasks modify the body of the 2-arg `computeIntensive` but keep these signatures.

- [ ] **Step 1: Write the failing tests**

Create `core/src/test/scala/ox/ComputeIntensiveTest.scala`:

```scala
package ox

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.atomic.AtomicBoolean

class ComputeIntensiveTest extends AnyFlatSpec with Matchers:
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
end ComputeIntensiveTest
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `sbt "core/testOnly ox.ComputeIntensiveTest"`
Expected: compilation error — `computeIntensive` / `setOxComputeExecutor` not found.

- [ ] **Step 3: Write the implementation**

Create `core/src/main/scala/ox/computeIntensive.scala`:

```scala
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

/** Runs `f` on the compute-intensive executor ([[oxComputeExecutor]]), blocking the calling (virtual) thread until it completes. Returns
  * the result of `f`, or rethrows the exception with which it failed.
  *
  * Use for long-running, CPU-intensive computations: virtual threads are not preempted, so run directly in a fork, such a computation
  * would monopolize a carrier thread of the virtual thread scheduler, potentially starving other virtual threads. The computation is
  * instead run on a pool of platform threads (by default sized to the number of available processors), which the OS schedules preemptively.
  * For short computation bursts, which can be instrumented with periodic yields, see [[cede]] as a lighter-weight alternative.
  *
  * As the calling thread blocks until the computation completes, the computation never outlives the enclosing concurrency scope (if any):
  * usage remains structured. To evaluate `f` in parallel with other code, combine with a fork, e.g. `fork(computeIntensive(f))`.
  *
  * If the calling thread is interrupted (e.g. because the enclosing scope ends), the thread running the computation becomes interrupted as
  * well, and the call keeps waiting until the computation completes. The computation can co-operate in the cancellation protocol using
  * [[checkInterrupt]] or [[cede]]. If the computation hasn't yet started when the interruption occurs, it will never run.
  *
  * The scope context is not propagated to the computation: [[ForkLocal]]s read their default values, and forks can't be created within
  * `f` (this fails with an [[IllegalStateException]]).
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
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `sbt "core/testOnly ox.ComputeIntensiveTest"`
Expected: 4 tests PASS.

- [ ] **Step 5: Format and commit**

```bash
sbt "core/scalafmtAll"
git add core/src/main/scala/ox/computeIntensive.scala core/src/test/scala/ox/ComputeIntensiveTest.scala
git commit -m "Add computeIntensive: run CPU-intensive operations on a platform-thread pool"
```

---

### Task 2: Interruption semantics

**Files:**
- Modify: `core/src/main/scala/ox/computeIntensive.scala` (replace the body of the 2-arg `computeIntensive`; add `ComputeIntensiveTask`)
- Modify (test): `core/src/test/scala/ox/ComputeIntensiveTest.scala` (append tests)

**Interfaces:**
- Consumes: `computeIntensive` signatures from Task 1; `ox.checkInterrupt()` (control.scala); `forkCancellable`, `forkDiscard`, `supervised`, `sleep` (existing ox API).
- Produces: unchanged public signatures. Internal: `private class ComputeIntensiveTask[T](executor: ExecutorService, f: () => T)` with `def submitAndAwait(): T` — Task 3 modifies its `run()` method and the `computeIntensive` entry point.

Semantics implemented here (from the spec):
1. Caller already interrupted on entry → `InterruptedException` immediately, nothing submitted.
2. Caller interrupted while waiting, task not yet started → task marked cancelled (never runs), IE thrown immediately.
3. Caller interrupted while waiting, task running → worker thread interrupted, caller **keeps waiting until the task completes**, then IE is thrown (task's exception, if any, added as suppressed; further caller interrupts recorded as suppressed too).
4. The interrupt delivered to the worker must not leak to a subsequent task on the reused pool thread.

- [ ] **Step 1: Write the failing tests**

Append to `ComputeIntensiveTest` (before `end ComputeIntensiveTest`), and add the needed imports at the top of the file:

```scala
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.*
```

```scala
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
  }
```

- [ ] **Step 2: Run tests to verify the new ones fail**

Run: `sbt "core/testOnly ox.ComputeIntensiveTest"`
Expected: "throw immediately when the caller is already interrupted" FAILS (`ran.get()` is `true`, or no IE thrown — the Task 1 implementation always submits). "never run the computation if it is cancelled before it started" FAILS (`secondRan` is `true`). The interrupt-and-await test may hang or fail: `cleanupDone` false. (If a test hangs, kill and treat as failure.)

- [ ] **Step 3: Implement the state machine**

In `computeIntensive.scala`, replace the 2-arg `computeIntensive` with:

```scala
/** As [[computeIntensive]], but runs `f` on the given `executor`, instead of the default [[oxComputeExecutor]]. */
def computeIntensive[T](executor: ExecutorService)(f: => T): T =
  checkInterrupt()
  new ComputeIntensiveTask(executor, () => f).submitAndAwait()
```

Add below (same file), together with the imports `java.util.concurrent.ExecutionException`:

```scala
private enum ComputeTaskState:
  case Pending, Running, Done, CancelledBeforeStart

private class ComputeIntensiveTask[T](executor: ExecutorService, f: () => T):
  private val lock = new Object
  // both fields guarded by lock
  private var state: ComputeTaskState = ComputeTaskState.Pending
  private var worker: Thread = null

  private val result = new CompletableFuture[T]()

  def submitAndAwait(): T =
    executor.execute(() => run())
    try unwrapExecutionException(result.get())
    catch case e: InterruptedException => onCallerInterrupted(e)

  private def run(): Unit =
    val proceed = lock.synchronized {
      if state == ComputeTaskState.CancelledBeforeStart then false
      else
        state = ComputeTaskState.Running
        worker = Thread.currentThread()
        true
    }
    if proceed then
      try
        val r = f()
        completing(result.complete(r).discard)
      catch case t: Throwable => completing(result.completeExceptionally(t).discard)
  end run

  // completes the result while holding the lock, clearing the worker's interrupt flag: an interrupt delivered by an
  // (interrupted) caller must never leak to a subsequent task executing on the reused pool thread
  private def completing(c: => Unit): Unit =
    lock.synchronized {
      state = ComputeTaskState.Done
      worker = null
      Thread.interrupted().discard
      c
    }

  private def onCallerInterrupted(e: InterruptedException): Nothing =
    val cancelledBeforeStart = lock.synchronized {
      state match
        case ComputeTaskState.Pending =>
          state = ComputeTaskState.CancelledBeforeStart
          true
        case ComputeTaskState.Running =>
          worker.interrupt()
          false
        case ComputeTaskState.Done                 => false
        case ComputeTaskState.CancelledBeforeStart => true
    }
    if !cancelledBeforeStart then
      // strict structure: the computation must complete before we return; recording, but not acting on, further interrupts
      var done = false
      while !done do
        try
          result.get().discard
          done = true
        catch
          case e2: InterruptedException => e.addSuppressed(e2)
          case _: ExecutionException    => done = true
      if result.isCompletedExceptionally then e.addSuppressed(result.exceptionNow())
    end if
    throw e
  end onCallerInterrupted
end ComputeIntensiveTask
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `sbt "core/testOnly ox.ComputeIntensiveTest"`
Expected: 7 tests PASS.

- [ ] **Step 5: Format and commit**

```bash
sbt "core/scalafmtAll"
git add core/src/main/scala/ox/computeIntensive.scala core/src/test/scala/ox/ComputeIntensiveTest.scala
git commit -m "computeIntensive: interrupt-then-await cancellation semantics"
```

---

### Task 3: Executor-aware nesting

**Files:**
- Modify: `core/src/main/scala/ox/computeIntensive.scala`
- Modify (test): `core/src/test/scala/ox/ComputeIntensiveTest.scala` (append tests)

**Interfaces:**
- Consumes: `ComputeIntensiveTask` from Task 2.
- Produces: unchanged public signatures. Internal: `private val currentComputeExecutor: ThreadLocal[ExecutorService]`.

Rule (from the spec): a nested `computeIntensive` targeting the **same** executor as the one the current thread is already running a compute task on runs `f` inline (fixed pools deadlock when workers block on tasks queued behind them). A nested call targeting a **different** executor performs a normal submit-and-wait.

- [ ] **Step 1: Write the failing tests**

Append to `ComputeIntensiveTest` (import `java.util.concurrent.atomic.AtomicInteger` at the top):

```scala
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
```

- [ ] **Step 2: Run tests to verify the new ones fail**

Run: `sbt "core/testOnly ox.ComputeIntensiveTest"`
Expected: "run a nested computation targeting the same executor inline" FAILS (different threads); "not deadlock nested computations" HANGS (kill it — that's the deadlock being tested); "different executor" PASSES already (submit-and-wait is the current behavior for all nested calls).

- [ ] **Step 3: Implement inline nesting**

In `computeIntensive.scala`, add (top level, near `customComputeExecutor`):

```scala
// the executor which is running the current thread's computeIntensive task, if any: nested computeIntensive calls
// targeting the same executor run inline (a fixed pool would deadlock, when all workers block on tasks queued behind them)
private val currentComputeExecutor = new ThreadLocal[ExecutorService]()
```

Change the 2-arg `computeIntensive` body to:

```scala
def computeIntensive[T](executor: ExecutorService)(f: => T): T =
  checkInterrupt()
  if currentComputeExecutor.get() eq executor then f
  else new ComputeIntensiveTask(executor, () => f).submitAndAwait()
```

In `ComputeIntensiveTask.run()`, wrap the execution of `f` so the thread-local is set while the task runs:

```scala
    if proceed then
      currentComputeExecutor.set(executor)
      try
        val r = f()
        completing(result.complete(r).discard)
      catch case t: Throwable => completing(result.completeExceptionally(t).discard)
      finally currentComputeExecutor.remove()
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `sbt "core/testOnly ox.ComputeIntensiveTest"`
Expected: 10 tests PASS.

- [ ] **Step 5: Format and commit**

```bash
sbt "core/scalafmtAll"
git add core/src/main/scala/ox/computeIntensive.scala core/src/test/scala/ox/ComputeIntensiveTest.scala
git commit -m "computeIntensive: executor-aware inline nesting"
```

---

### Task 4: Isolation & interop characterization tests

**Files:**
- Modify (test): `core/src/test/scala/ox/ComputeIntensiveTest.scala` (append tests)

**Interfaces:**
- Consumes: the complete `computeIntensive` from Tasks 1–3; `ForkLocal`, `supervised`, `fork`, `forkDiscard`, `either` + `ox.either.ok` (existing ox API).
- Produces: nothing new — these tests pin down emergent properties required by the spec (no scope-context propagation, `either` interop, no starvation).

These are characterization tests: the behavior should already hold. Write them, run them; if any fails, that's a bug in Tasks 1–3 — fix there, don't adjust the test.

- [ ] **Step 1: Write the tests**

Append to `ComputeIntensiveTest`, adding `import ox.either.ok` at the top of the file:

```scala
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
```

- [ ] **Step 2: Run the tests**

Run: `sbt "core/testOnly ox.ComputeIntensiveTest"`
Expected: 14 tests PASS. If "not starve virtual threads" hangs or times out, the pool is not made of platform threads (or is shared with the VT scheduler) — a Task 1 bug. If "not propagate fork locals" or "not allow forking" fails, scope context is leaking — a Task 2/3 bug.

- [ ] **Step 3: Format and commit**

```bash
sbt "core/scalafmtAll"
git add core/src/test/scala/ox/ComputeIntensiveTest.scala
git commit -m "computeIntensive: isolation and interop tests"
```

---

### Task 5: `OxApp.Settings` integration

**Files:**
- Modify: `core/src/main/scala/ox/OxApp.scala`
- Modify (test): `core/src/test/scala/ox/OxAppTest.scala` (append one test)

**Interfaces:**
- Consumes: `setOxComputeExecutor` from Task 1.
- Produces: `OxApp.Settings.computeExecutor: Option[ExecutorService]` (new last field of the case class), applied at app startup.

- [ ] **Step 1: Write the failing test**

Append inside the `OxAppTest` class (match its existing test style; add imports only if `Matchers` isn't already mixed in — it is):

```scala
  "OxApp.Settings" should "default to no custom compute executor" in {
    OxApp.Settings.Default.computeExecutor shouldBe None
  }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `sbt "core/testOnly ox.OxAppTest"`
Expected: compilation error — `computeExecutor` is not a member of `Settings`.

- [ ] **Step 3: Extend `Settings`**

In `core/src/main/scala/ox/OxApp.scala`:

a. Add the import (next to the existing `java.util.concurrent.ThreadFactory`):

```scala
import java.util.concurrent.ExecutorService
```

b. In the `Settings` scaladoc, add a `@param` after the `threadFactory` one:

```scala
    * @param computeExecutor
    *   The executor used to run computations passed to [[computeIntensive]]. Platform (OS-preempted) threads should be used, so that
    *   CPU-intensive computations don't monopolize the virtual thread scheduler's carrier threads. If left unspecified, the default
    *   fixed pool, sized to the number of available processors, is used.
```

c. Replace the `Settings` case class (keeping the existing 4-arg `this` and `copy` overloads' behavior) with:

```scala
  case class Settings(
      interruptedExitCode: ExitCode,
      handleInterruptedException: InterruptedException => Unit,
      handleException: Throwable => Unit,
      threadFactory: Option[ThreadFactory],
      shutdownTimeout: FiniteDuration,
      computeExecutor: Option[ExecutorService]
  ):
    // required for binary compatibility
    def this(
        interruptedExitCode: ExitCode,
        handleInterruptedException: InterruptedException => Unit,
        handleException: Throwable => Unit,
        threadFactory: Option[ThreadFactory]
    ) = this(interruptedExitCode, handleInterruptedException, handleException, threadFactory, 10.seconds, None)

    // required for binary compatibility
    def this(
        interruptedExitCode: ExitCode,
        handleInterruptedException: InterruptedException => Unit,
        handleException: Throwable => Unit,
        threadFactory: Option[ThreadFactory],
        shutdownTimeout: FiniteDuration
    ) = this(interruptedExitCode, handleInterruptedException, handleException, threadFactory, shutdownTimeout, None)

    // required for binary compatibility
    def copy(
        interruptedExitCode: ExitCode,
        handleInterruptedException: InterruptedException => Unit,
        handleException: Throwable => Unit,
        threadFactory: Option[ThreadFactory]
    ): Settings = Settings(interruptedExitCode, handleInterruptedException, handleException, threadFactory, shutdownTimeout, computeExecutor)

    // required for binary compatibility
    def copy(
        interruptedExitCode: ExitCode,
        handleInterruptedException: InterruptedException => Unit,
        handleException: Throwable => Unit,
        threadFactory: Option[ThreadFactory],
        shutdownTimeout: FiniteDuration
    ): Settings = Settings(interruptedExitCode, handleInterruptedException, handleException, threadFactory, shutdownTimeout, computeExecutor)
  end Settings
```

d. In the `Settings` companion, add a 5-arg `apply` overload next to the existing 4-arg one (both marked "required for binary compatibility"), and extend `Default`:

```scala
    // required for binary compatibility
    def apply(
        interruptedExitCode: ExitCode,
        handleInterruptedException: InterruptedException => Unit,
        handleException: Throwable => Unit,
        threadFactory: Option[ThreadFactory]
    ): Settings =
      Settings(interruptedExitCode, handleInterruptedException, handleException, threadFactory, 10.seconds, None)

    // required for binary compatibility
    def apply(
        interruptedExitCode: ExitCode,
        handleInterruptedException: InterruptedException => Unit,
        handleException: Throwable => Unit,
        threadFactory: Option[ThreadFactory],
        shutdownTimeout: FiniteDuration
    ): Settings =
      Settings(interruptedExitCode, handleInterruptedException, handleException, threadFactory, shutdownTimeout, None)
```

```scala
    val Default: Settings =
      Settings(ExitCode.Success, defaultHandleInterruptedException(DefaultLogException), DefaultLogException, None, 10.seconds, None)
```

e. Where the app applies settings (`settings.threadFactory.foreach(setOxThreadFactory)`, around line 36), add directly below:

```scala
    settings.computeExecutor.foreach(setOxComputeExecutor)
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `sbt "core/testOnly ox.OxAppTest ox.ComputeIntensiveTest"`
Expected: all PASS. (The wiring of `computeExecutor` → `setOxComputeExecutor` is verified by compilation + the Default test only: actually invoking it would poison the JVM-global executor for other suites.)

- [ ] **Step 5: Format and commit**

```bash
sbt "core/scalafmtAll"
git add core/src/main/scala/ox/OxApp.scala core/src/test/scala/ox/OxAppTest.scala
git commit -m "computeIntensive: configure the compute executor via OxApp.Settings"
```

---

### Task 6: Documentation

**Files:**
- Create: `doc/utils/compute-intensive.md`
- Modify: `doc/index.md` (add `utils/compute-intensive` to the toctree, directly after the `utils/control-flow` line, ~line 81)
- Modify: `doc/utils/control-flow.md` (extend the `cede()` bullet)
- Modify: `core/src/main/scala/ox/control.scala` (extend `cede()` scaladoc)

**Interfaces:**
- Consumes: final API from Tasks 1–5.
- Produces: user-facing docs; no code changes beyond one scaladoc sentence.

- [ ] **Step 1: Write the doc page**

Create `doc/utils/compute-intensive.md`:

```markdown
# CPU-intensive operations

Ox runs forks on virtual threads, which are not preempted by the JVM's scheduler: a virtual thread yields control only
when it performs a blocking operation. A long-running, CPU-intensive computation which doesn't call any blocking
operations monopolizes a carrier thread of the virtual thread scheduler. Since there are as many carrier threads as CPU
cores (by default), a handful of such computations can starve all other virtual threads in the process — including
latency-sensitive ones.

There are two remedies, depending on the kind of computation:

* for short computation bursts in code that you control, insert periodic [`cede()`](control-flow.md) calls, which yield
  the virtual thread back to the scheduler,
* for long-running computations, or code which can't be instrumented with yields (e.g. third-party libraries), use
  `computeIntensive`.

## computeIntensive

`computeIntensive(f)` runs the computation `f` on a pool of **platform** threads, blocking the calling (virtual) thread
until it completes; the result is returned (or the computation's exception rethrown). Platform threads are scheduled
preemptively by the operating system, so however long the computation runs, virtual threads keep making progress.

```scala
import ox.*

def expensive(): BigInt = (1 to 1_000_000).map(BigInt(_)).product

val result: BigInt = computeIntensive(expensive())
```

Because the caller blocks until the computation completes, `computeIntensive` is structured: the computation never
outlives the enclosing concurrency scope. To run computations in parallel with other code, combine with forks:

```scala
supervised {
  val f1 = fork(computeIntensive(expensive()))
  val f2 = fork(computeIntensive(expensive()))
  f1.join() + f2.join()
}
```

## The compute pool

By default, computations run on a lazily-created global pool of daemon platform threads, sized to the number of
available processors. The pool can be replaced application-wide using `setOxComputeExecutor` (before any
`computeIntensive` calls; also available as `OxApp.Settings.computeExecutor`), e.g. to use a smaller pool in
latency-sensitive deployments, reserving cores for the virtual thread scheduler. A per-call executor can be given as
well: `computeIntensive(executor)(f)`.

Nested `computeIntensive` calls targeting the same executor run inline, on the current (pool) thread; calls targeting a
different executor are submitted to that executor.

## Cancellation

If the calling thread is interrupted — typically because the enclosing scope ends, e.g. due to an error in another
fork — the thread running the computation becomes interrupted as well, and the caller keeps waiting until the
computation completes. That way, the computation never runs past the lifetime of its scope. Computations should
co-operate in the cancellation protocol by calling `checkInterrupt()` (or `cede()`) periodically; a computation which
doesn't will delay the scope's shutdown until it completes. If the computation hasn't yet started when the cancellation
occurs, it will never run.

Note that consequently, `timeout(...)(computeIntensive(f))` can't return before the computation notices the
interruption — with a non-cooperating computation, the timeout will overshoot arbitrarily.

## Scope context

The scope context is not propagated to the computation: [fork locals](../structured-concurrency/fork-local.md) read
their default values within `f`, and forks can't be created there (this fails with an `IllegalStateException`). The
same applies to thread-local-based integrations, such as MDC or OpenTelemetry contexts.
```

- [ ] **Step 2: Register the page & cross-link**

In `doc/index.md`, after the `   utils/control-flow` line, add:

```
   utils/compute-intensive
```

In `doc/utils/control-flow.md`, extend the `cede()` bullet (after "...a single call costs about 1µs)"):

```markdown
  For long-running computations, or code which can't be instrumented with yields, see
  [CPU-intensive operations](compute-intensive.md)
```

- [ ] **Step 3: Extend the `cede()` scaladoc**

In `core/src/main/scala/ox/control.scala`, in the `cede()` scaladoc, after the paragraph ending "...at a much higher cost (about 40µs per call, as it involves a timer round-trip).", add:

```scala
  * For long-running computations, or code which can't be instrumented with yields, consider [[computeIntensive]], which runs the
  * computation on a pool of OS-preempted platform threads.
  *
```

- [ ] **Step 4: Verify**

Run: `sbt "core/compile" "core/Test/compile"`
Expected: success (scaladoc references resolve at compile time only if using `-Xdoc` checks — a clean compile suffices).

Then run the full core suite once, as final verification: `sbt "core/test"`
Expected: all tests PASS.

- [ ] **Step 5: Format and commit**

```bash
sbt "core/scalafmtAll"
git add doc/utils/compute-intensive.md doc/index.md doc/utils/control-flow.md core/src/main/scala/ox/control.scala
git commit -m "computeIntensive: documentation"
```
