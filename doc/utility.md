# Utilities

In addition to concurrency, error handling and resiliency features, ox includes some utility methods, which make writing
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

## Boundary/break for `Either`s

To streamline working with `Either` values, especially when used for [error handling](error-handling.md), ox provides
a specialised version of the [boundary/break](https://www.scala-lang.org/api/current/scala/util/boundary$.html) 
mechanism. 

Within a code block passed to `either`, it allows "unwrapping" `Either`s using `.value`. The unwrapped value corresponds 
to the right side of the `Either`, which by convention represents successful computations. In case a failure is 
encountered (a left side of an `Either`), the computation is short-circuited, and the failure becomes the result. 

For example:

```scala mdoc:compile-only
import ox.either
import ox.either.value

case class User()
case class Organization()
case class Assignment(user: User, org: Organization)

def lookupUser(id1: Int): Either[String, User] = ???
def lookupOrganization(id2: Int): Either[String, Organization] = ???

val result: Either[String, Assignment] = either:
  val user = lookupUser(1).value
  val org = lookupOrganization(2).value
  Assignment(user, org)
```

You can also use union types to accumulate different types of errors, e.g.:

```scala
val v1: Either[Int, String] = ???
val v2: Either[Long, String] = ???

val result: Either[Int | Long, String] = either:
  v1.value ++ v2.value
```

Finally, options can be unwrapped as well; the error type is then `Unit`:

```scala
val v1: Option[String] = ???
val v2: Option[Int] = ???

val result: Either[Unit, String] = either:
  v1.value * v2.value
```
