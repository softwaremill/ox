# Control flow methods

There are some helper methods which might be useful when writing code using ox's concurrency operators:

* `forever { ... }` repeatedly evaluates the given code block forever
* `repeatWhile { ... }` repeatedly evaluates the given code block, as long as it returns `true`
* `repeatUntil { ... }` repeatedly evaluates the given code block, until it returns `true`
* `never` blocks the current thread indefinitely, until it is interrupted
* `checkInterrupt()` checks if the current thread is interrupted, and if so, throws an `InterruptedException`. Useful in
  compute-intensive code, which wants to cooperate in the cancellation protocol
* `cede()` yields the current (virtual) thread back to the scheduler, allowing other threads to run, and checks for
  interruption (as `checkInterrupt()` does). Useful in compute-intensive code, which doesn't otherwise call any blocking
  operations: virtual threads are not preempted, so without yielding, a CPU-bound loop can starve other virtual threads.
  As a rule of thumb, call `cede()` about once every millisecond of computation (a single call costs about 1µs)

All of these are `inline` methods, imposing no runtime overhead.
