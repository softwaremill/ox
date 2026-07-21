# `computeIntensive`: running CPU-intensive operations off the virtual-thread scheduler

Date: 2026-07-21
Status: approved

## Problem

Ox runs all forks on virtual threads. The JDK's virtual-thread scheduler (a FIFO
ForkJoinPool with parallelism = available processors) does not implement time
sharing (JEP 444): a CPU-bound computation that doesn't call any blocking
operation holds its carrier thread until it completes. Once all carriers are
occupied by such computations, every other virtual thread in the process
starves — including latency-sensitive ones (e.g. health-check endpoints).

`cede()` (added in #481) is a cooperative mitigation for code that can sprinkle
yield points. It doesn't help for computations that can't be instrumented
(third-party libraries, tight numeric kernels), and `Thread.yield()`-based
fairness is not guaranteed by the JDK.

## State of the art (research summary)

- **JDK/Loom official guidance** (Oracle core-libraries docs, JEP 444, Ron
  Pressler's "State of Loom"): virtual threads are not intended for
  long-running CPU-bound work; the named escape hatch is to run such work on
  platform threads and "rely on the kernel scheduler". Forced preemption was
  prototyped in 2020 but never merged; no JEP exists. Experimental custom
  virtual-thread schedulers (`Thread.VirtualThreadScheduler`) exist only in the
  Loom repo's fibers branch as of mid-2026 — not something to build on.
- **Go** solved this in the runtime (signal-based preemption, ~10 ms slices,
  since 1.14) — not available to JVM libraries.
- **Cats Effect 3 / ZIO 2** auto-yield between interpreter steps (every
  1024/2048 ops) — impossible in direct style, where there is no interpreter
  loop to instrument — and offer explicit yields plus executor-shifting
  combinators (`evalOn`, `ZIO.onExecutor`).
- **Kotlin coroutines**: `withContext(Dispatchers.Default)` shifts a block onto
  a CPU pool sized max(cores, 2); the closest direct-style analog to what ox
  needs.
- **Tokio**: "async is not for CPU-bound" — use a dedicated compute pool
  (rayon), bridge results back.

Cross-cutting conclusion: without runtime preemption, the standard solution is
a wrapper that shifts the computation to a dedicated pool of OS-preemptible
platform threads, sized to the core count, with the result bridged back to the
caller.

## Design

### Core API

A new top-level function (new file `core/src/main/scala/ox/computeIntensive.scala`):

```scala
/** Runs `f` on the compute-intensive pool, blocking the calling (virtual)
  * thread until it completes; returns its result or rethrows its exception. */
def computeIntensive[T](f: => T): T

/** As above, but runs `f` on the given executor. */
def computeIntensive[T](executor: ExecutorService)(f: => T): T
```

Blocking the caller makes the construct structured by construction: the
computation cannot outlive the enclosing scope, because the calling fork
doesn't complete until the computation does. No changes to `ThreadHerd` or
supervision are needed, and it composes with all existing combinators:
`fork(computeIntensive(...))`, `par`, `race`, `timeout`. It may also be called
outside of any scope — it's an ordinary blocking call.

### Default pool and pluggability

- Lazily created on first use; global; fixed size =
  `Runtime.getRuntime.availableProcessors()`.
- Daemon **platform** threads, named `ox-compute-0`, `ox-compute-1`, … The OS
  preempts them, so a misbehaving computation cannot starve virtual-thread
  carriers; fairness among computations is the kernel's job.
- Unbounded task queue (fixed pool): submissions never block or reject;
  parallelism is bounded at the core count.
- Sizing note: with the compute pool and the virtual-thread carriers both
  saturated, the process runs ~2× cores runnable threads. This is intended —
  the OS time-slices between them (same as Kotlin's Default + IO dispatchers
  coexisting). Latency-sensitive deployments that want to reserve cores for
  the VT scheduler can plug a smaller pool via `setOxComputeExecutor` (cf.
  Cats Effect's advice to bound expensive work to ~half the cores).
- Globally replaceable via `setOxComputeExecutor(es: ExecutorService)`,
  mirroring `setOxThreadFactory`: must be called before first use, throws
  otherwise. Exposed also through `OxApp.Settings`.
- Per-call override via the `computeIntensive(executor)(f)` overload.
- The default pool is never shut down (daemon threads); a user-provided
  executor's lifecycle is the user's responsibility.

### Interruption semantics

If the calling thread is already interrupted on entry, `computeIntensive`
throws `InterruptedException` immediately, without submitting the task
(consistent with `checkInterrupt()`).

If the calling thread is interrupted while waiting (typically: the enclosing
scope ends):

1. If the task hasn't started yet, it is marked cancelled and will never run;
   `computeIntensive` throws `InterruptedException` immediately.
2. If the task is running, the pool thread executing it is interrupted, and
   the caller **continues waiting until the task actually completes** — strict
   structure: the computation never runs past the point where its scope ended.
   Then `InterruptedException` is thrown (with the task's exception, if any,
   added as suppressed).
3. Further interrupts of the caller while waiting in (2) are recorded and
   re-delivered via the thrown exception; they do not abandon the wait.

The computation is expected to cooperate via the existing `checkInterrupt()` /
`cede()`. A non-cooperating computation therefore delays scope shutdown until
it completes — consistent with how ox treats non-cooperating inline code.

Wrapping the call as `abandonOnInterrupt(computeIntensive(f))` lets the
*caller* return early on interrupt, but note the precise semantics: the wait
runs on a detached thread which is never interrupted, so the computation runs
to full completion, uninterrupted, holding a compute-pool slot after
abandonment. A true "interrupt the task but don't wait for it" variant is not
provided initially (see future directions).

Implementation note: `FutureTask.cancel(true)` + `get()` cannot express
"interrupt, then await completion" (after `cancel`, `get` throws immediately).
The bridge is a small custom state machine (CompletableFuture for the result +
an atomic started/cancelled flag + completion latch; the running worker thread
is recorded so the caller can interrupt precisely it), similar in spirit to
`forkCancellable`. The interrupt must be delivered such that it cannot leak to
a subsequent task on the reused pool thread (the task clears its own interrupt
flag after completing, under the state machine's lock).

### Nesting

Calling `computeIntensive` from code already running inside a
`computeIntensive` task, **targeting the same executor**, runs `f` inline on
the current thread. Otherwise a fixed pool deadlocks when all workers block
waiting on nested tasks that sit behind them in the queue.

The inline shortcut must be executor-aware: a nested call targeting a
*different* executor (via the per-call overload) must not run inline — that
would silently execute `f` on the wrong pool — but performs a normal
submit-and-wait, which is deadlock-free across distinct pools (cycles built
by the user across several custom pools remain their responsibility).
Detection: a thread-local, set by the wrapper task around the execution of
`f`, recording the executor the current task runs on — this works for custom
executors too.

### Scope context and locals

Deliberately **not** propagated to the pool thread:

- `ForkLocal`s read their default values inside the computation.
- Calling `fork` (or any scope-requiring operation) inside the computation
  fails fast with the existing "outside the tree of concurrency scopes"
  `IllegalStateException`.

Rationale: compute blocks are pure computation; keeping them free of scope
context avoids leaking scope references into threads that outlive the scope.
This is a visible difference vs. code running directly in a fork and must be
documented prominently (MDC/otel context integrations won't cross the
boundary either).

### Error handling

- Exceptions thrown by `f` are rethrown to the caller unchanged (unwrapped
  from any executor-level wrapper, reusing the `unwrapExecutionException`
  approach from `fork.scala`), so `either`-blocks, supervision and
  `race`/`par` error handling behave exactly as for inline code.
- `InterruptedException` thrown by `f` itself (cooperative cancellation)
  surfaces to the caller as described above.

### Documentation

- New doc page (e.g. `doc/utils/compute-intensive.md`, exact placement to
  follow existing doc structure) covering: why CPU-bound work starves virtual
  threads, when to use `cede()` (short, instrumentable bursts) vs
  `computeIntensive` (long-running or non-instrumentable compute), pool
  configuration, interruption behavior, and the no-scope-context caveat.
  Must also document that `timeout(...)(computeIntensive(f))` cannot return
  before the computation notices the interrupt — with non-cooperative compute
  the timeout overshoots arbitrarily (as for non-cooperating inline code).
- Cross-link from `doc/utils/control-flow.md`'s `cede()` section.
- Scaladoc on `cede()` gains a pointer to `computeIntensive`.

### Testing

- Result and exception propagation (including `either` interop).
- Interrupt-then-await: interrupting the caller interrupts the task, and the
  caller does not return until the task completes.
- Cancellation before start: task never runs, IE thrown immediately.
- Nested `computeIntensive` on the same executor runs inline (no deadlock
  even with pool size 1); nested call targeting a different executor runs on
  that executor, not inline.
- Calling `computeIntensive` with the calling thread already interrupted
  throws immediately, without running `f`.
- Custom executor overload and `setOxComputeExecutor` are honored.
- Starvation smoke test: with the pool saturated by spinning tasks, unrelated
  virtual threads keep making progress.
- `ForkLocal` returns default inside the computation; `fork` inside the
  computation throws.

## Non-goals / future directions

- No auto-yield or preemption emulation — impossible in direct style.
- No bounded-submission/backpressure semantics beyond the fixed pool size.
- `forkCompute` sugar (`fork(computeIntensive(f))`) — may be added later if
  demand appears.
- An "interrupt the task, but don't wait for it to complete" variant (a
  middle ground between the default strict-structure semantics and
  `abandonOnInterrupt`, which never interrupts the task) — only if a concrete
  need appears; it weakens the structured guarantee.
- When/if `Thread.VirtualThreadScheduler` ships in mainline JDK, revisit:
  compute-heavy forks could get a dedicated carrier pool instead of a bridged
  executor.
