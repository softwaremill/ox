# Control flow methods

There are some helper methods which might be useful when writing code using ox's concurrency operators:

* `forever { ... }` repeatedly evaluates the given code block forever
* `repeatWhile { ... }` repeatedly evaluates the given code block, as long as it returns `true`
* `repeatUntil { ... }` repeatedly evaluates the given code block, until it returns `true`
* `never` blocks the current thread indefinitely, until it is interrupted
* `checkInterrupt()` checks if the current thread is interrupted, and if so, throws an `InterruptedException`. Useful in
  compute-intensive code, which wants to cooperate in the cancellation protocol

All of these are `inline` methods, imposing no runtime overhead.
