# CPU-intensive operations

Ox runs forks on virtual threads, which are not preempted by the JVM's
scheduler: a virtual thread yields control only when it performs a blocking
operation. A long-running, CPU-intensive computation which doesn't call any
blocking operations monopolizes a carrier thread of the virtual thread
scheduler. Since there are as many carrier threads as CPU cores (by default), a
handful of such computations can starve all other virtual threads in the process
- including latency-sensitive ones.

There are two remedies, depending on the kind of computation:

* for short computation bursts in code that you control, insert periodic
  [`cede()`](control-flow.md) calls, which yield the virtual thread back to the
  scheduler,
* for long-running computations, or code which can't be instrumented with yields
  (e.g. third-party libraries), use `computeIntensive`.

## computeIntensive

`computeIntensive(f)` runs the computation `f` on a pool of **platform**
threads, blocking the calling (virtual) thread until it completes; the result is
returned (or the computation's exception rethrown). Platform threads are
scheduled preemptively by the operating system, so however long the computation
runs, virtual threads keep making progress.

```scala
import ox.*

def expensive(): BigInt = (1 to 1_000_000).map(BigInt(_)).product

val result: BigInt = computeIntensive(expensive())
```

Because the caller blocks until the computation completes, `computeIntensive` is
structured: the computation never outlives the enclosing concurrency scope. To
run computations in parallel with other code, combine with forks:

```scala
import ox.*

def expensive(): BigInt = (1 to 1_000_000).map(BigInt(_)).product

supervised {
  val f1 = fork(computeIntensive(expensive()))
  val f2 = fork(computeIntensive(expensive()))
  f1.join() + f2.join()
}
```

## The compute pool

By default, computations run on a lazily-created global pool of daemon platform
threads, sized to the number of available processors. The pool can be replaced
application-wide using `setOxComputeExecutor` (before any `computeIntensive`
calls; also available as `OxApp.Settings.computeExecutor`), e.g. to use a
smaller pool in latency-sensitive deployments, reserving cores for the virtual
thread scheduler. A per-call executor can be given as well:
`computeIntensive(executor)(f)`.

Nested `computeIntensive` calls targeting the same executor run inline, on the
current (pool) thread; calls targeting a different executor are submitted to
that executor.

Note that the lifecycle of a custom executor is the caller's responsibility:
once it is shut down, further submissions fail with a
`RejectedExecutionException`, and tasks dropped by `shutdownNow` never start
(their callers keep waiting, until interrupted).

## Cancellation

If the calling thread is interrupted - typically because the enclosing scope
ends, e.g. due to an error in another fork - the thread running the computation
becomes interrupted as well, and the caller keeps waiting until the computation
completes. That way, the computation never runs past the lifetime of its scope.
Computations should co-operate in the cancellation protocol by calling
`checkInterrupt()` (or `cede()`) periodically; a computation which doesn't will
delay the scope's shutdown until it completes. If the computation hasn't yet
started when the cancellation occurs, it will never run.

Note that consequently, `timeout(...)(computeIntensive(f))` can't return before
the computation notices the interruption - with a non-cooperating computation,
the timeout will overshoot arbitrarily.

To return early on interruption instead of waiting, the call can be wrapped as
`abandonOnInterrupt(computeIntensive(f))`
- note the precise semantics: the wait then runs on a detached thread which is
never interrupted, so the computation runs to full completion, uninterrupted,
holding a compute-pool slot even after the wait was abandoned.

## Scope context

The scope context is not propagated to the computation: [fork
locals](../structured-concurrency/fork-local.md) read their default values
within `f`, and forks can't be created there (this fails with an
`IllegalStateException`). The same applies to thread-local-based integrations,
such as MDC or OpenTelemetry contexts.
