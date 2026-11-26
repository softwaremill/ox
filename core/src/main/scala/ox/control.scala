package ox

import java.util.concurrent.locks.LockSupport

/** Repeat evaluating `f` forever. */
inline def forever(inline f: Unit): Nothing =
  while true do f
  throw new RuntimeException("can't get here")

/** Repeat evaluating `f` while it evaluates to `true`. */
inline def repeatWhile(inline f: Boolean): Unit =
  var loop = true
  while loop do loop = f

/** Repeat evaluating `f` until it evaluates to `true`. */
inline def repeatUntil(inline f: Boolean): Unit =
  var loop = true
  while loop do loop = !f

/** Blocks the current thread indefinitely, until it is interrupted. */
inline def never: Nothing = forever {
  LockSupport.park()
  if Thread.interrupted() then throw new InterruptedException()
}

/** Checks if the current thread is interrupted. Useful in compute-intensive code, which wants to cooperate in the cancellation protocol,
  * e.g. when run in a [[supervised]] scope.
  *
  * @throws InterruptedException
  *   if the current thread is interrupted.
  */
inline def checkInterrupt(): Unit =
  if Thread.interrupted() then throw new InterruptedException()

/** Yields the current (virtual) thread back to the scheduler, allowing other threads to run. Useful in compute-intensive code, which
  * doesn't otherwise call any blocking operations: virtual threads are not preempted, so without yielding, a CPU-bound loop can starve
  * other virtual threads. As a rule of thumb, call `cede()` about once every millisecond of computation (a single call costs about 1µs).
  *
  * Additionally, checks if the current thread is interrupted (as [[checkInterrupt]] does), making the surrounding computation cooperate in
  * the cancellation protocol, e.g. when run in a [[supervised]] scope.
  *
  * Yielding is implemented using `Thread.yield`, which prevents complete starvation of other threads, but doesn't guarantee a fair
  * distribution of CPU time. If strict fairness between CPU-bound threads is required, `LockSupport.parkNanos(1)` yields near-round-robin
  * scheduling, at a much higher cost (about 40µs per call, as it involves a timer round-trip).
  *
  * For long-running computations, or code which can't be instrumented with yields, consider [[computeIntensive]], which runs the
  * computation on a pool of OS-preempted platform threads.
  *
  * @throws InterruptedException
  *   if the current thread is interrupted.
  */
inline def cede(): Unit =
  checkInterrupt()
  Thread.`yield`()
