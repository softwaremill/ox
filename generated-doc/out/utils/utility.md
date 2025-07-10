# Utilities

In addition to concurrency, error handling and resiliency features, Ox includes some utility methods, which make writing
direct-style Scala code more convenient. When possible, these are `inline` methods taking `inline` parameters, hence 
incurring no runtime overhead.

Top-level methods:

* `uninterruptible { ... }` evaluates the given code block making sure it can't be interrupted
* `sleep(scala.concurrent.Duration)` blocks the current thread/fork for the given duration; same as `Thread.sleep`, but
  using's Scala's `Duration` 
* `debug(expression)` prints the code representing the expression, and the value of the expression to standard output,
  using `println`.

Extension functions on arbitrary expressions:

* `.discard` extension method evaluates the given code block and discards its result, avoiding "discarded non-unit 
  value" warnings
* `.pipe(f)` applies `f` to the value of the expression and returns the result; useful for chaining operations
* `.tap(f)` applies `f` to the value of the expression and returns the original value; useful for side-effecting 
  operations
* `.tapException(Throwable => Unit)` and `.tapNonFatalException(Throwable => Unit)` allow running the provided 
  side-effecting callback when the expression throws an exception
* `.debug(label)` prints the value preceeded with the given label, and returns the original value

Extension functions on `scala.concurrent.Future[T]`:

* `.get(): T` blocks the current thread/fork until the future completes; returns the successful value of the future, or 
  throws the exception, with which it failed

## Examples

Debug utilities:

```scala
import ox.*

val x = 20
// x: Int = 20
val y = 10
// y: Int = 10
debug(x * 2 + y)
// MdocApp.this.x.*(2).+(MdocApp.this.y) = 50
```

```scala
import ox.*

def transform(n: Int): Long = n * n * n

transform(5).debug("transformation result")
// transformation result: 125
// res1: Long = 125L
```