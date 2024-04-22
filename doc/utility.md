# Utilities

In addition to concurrency, error handling and resiliency features, Ox includes some utility methods, which make writing
direct-style Scala code more convenient. When possible, these are `inline` methods taking `inline` parameters, hence 
incurring no runtime overhead.

Top-level methods:

* `uninterruptible { ... }` evaluates the given code block making sure it can't be interrupted
* `sleep(scala.concurrent.Duration)` blocks the current thread/fork for the given duration; same as `Thread.sleep`, but
  using's Scala's `Duration` 

Extension functions on arbitrary expressions:

* `.discard` extension method evaluates the given code block and discards its result, avoiding "discarded non-unit 
  value" warnings
* `.pipe(f)` applies `f` to the value of the expression and returns the result; useful for chaining operations
* `.tap(f)` applies `f` to the value of the expression and returns the original value; useful for side-effecting 
  operations
* `.tapException(Throwable => Unit)` and `.tapNonFatalException(Throwable => Unit)` allow running the provided 
  side-effecting callback when the expression throws an exception

Extension functions on `scala.concurrent.Future[T]`:

* `.get(): T` blocks the current thread/fork until the future completes; returns the successful value of the future, or 
  throws the exception, with which it failed

