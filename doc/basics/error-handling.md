# Error handling

Ox uses two channels, through which errors can be signalled:

1. exceptions: used in case of bugs, unexpected situations, when integrating with Java libraries
2. application/logical errors: represented as values, using `Either`s, or as part of a custom data type

## Exceptions

Exceptions are always appropriately handled by computation combinators, such as the high-level concurrency operations
[`par`](../high-level-concurrency/par.md) and [`race`](../high-level-concurrency/race.md), as well as by 
[scopes](../structured-concurrency/fork-join.md) and [channels](../channels/index.md).

The general rule for computation combinators is that using them should throw exactly the same exceptions, as if the 
provided code was executed without them. That is, no additional exceptions might be thrown, and no exceptions are 
swallowed. The only difference is that some exceptions might be added as suppressed (e.g. interrupted exceptions).

Some examples of exception handling in Ox include:

* short-circuiting in `par` and `race` when one of the computations fails
* retrying computations in `retry` when they fail
* ending a `supervised` concurrency scope when a supervised fork fails

Exceptions can be handled using the `try/catch/finally` mechanism.

## Application errors

Some of the functionalities provided by Ox also support application-level errors. Such errors are represented as values,
e.g. the left side of an `Either[MyError, MyResult]`. They are not thrown, but returned from the computations which
are orchestrated by Ox.

Ox must be made aware of how such application errors are represented. This is done through an `ErrorMode`. Provided
implementations include `EitherMode[E]` (where left sides of `Either`s are used to represent errors), and 
`UnionMode[E]`, where a union type of `E` and a successful value is used. Arbitrary user-provided implementations
are possible as well.

Error modes can be used in [`supervisedError`](../structured-concurrency/error-handling-scopes.md) scopes, as well as in variants of the `par`, `race`, `retry` 
methods, and others.

```{note}
Using application errors allows specifying the possible errors in the type signatures of the methods, and is hence 
more type-safe. If used consistently, exceptions might be avoided altogether, except for signalling bugs in the code.
However, representing errors as values might incur a syntax overhead, and might be less convenient in some cases.
Moreover, all I/O libraries typically throw exceptions - to use them with errors-as-values, one would need to provide
a wrapper which would convert such exceptions to values. Hence, while application errors provide a lot of benefits,
they are not a universal solution to error handling.
```

## Boundary/break for `Either`s

To streamline working with `Either` values, Ox provides a specialised version of the 
[boundary/break](https://www.scala-lang.org/api/current/scala/util/boundary$.html) mechanism.

Within a code block passed to `either`, it allows "unwrapping" `Either`s using `.ok()`. The unwrapped value corresponds
to the right side of the `Either`, which by convention represents successful computations. In case a failure is
encountered (a left side of an `Either`), the computation is short-circuited, and the failure becomes the result.

For example:

```scala mdoc:compile-only
import ox.either
import ox.either.ok

case class User()
case class Organization()
case class Assignment(user: User, org: Organization)

def lookupUser(id1: Int): Either[String, User] = ???
def lookupOrganization(id2: Int): Either[String, Organization] = ???

val result: Either[String, Assignment] = either:
  val user = lookupUser(1).ok()
  val org = lookupOrganization(2).ok()
  Assignment(user, org)
```

You can also use union types to accumulate different types of errors, e.g.:

```scala mdoc:compile-only
import ox.either
import ox.either.ok

val v1: Either[Int, String] = ???
val v2: Either[Long, String] = ???

val result: Either[Int | Long, String] = either:
  v1.ok() ++ v2.ok()
```

Finally, options can be unwrapped as well; the error type is then `Unit`:

```scala mdoc:compile-only
import ox.either
import ox.either.ok

val v1: Option[String] = ???
val v2: Option[Int] = ???

val result: Either[Unit, String] = either:
  v1.ok() * v2.ok()
```

Failures can be reported using `.fail()`. For example (although a pattern match would be better in such a simple case):

```scala mdoc:compile-only
import ox.either
import ox.either.{fail, ok}

val v1: Either[String, Int] = ???

val result: Either[String, Int] = either:
  if v1.ok() > 10 then 42 else "wrong".fail()
```

Exception-throwing code can be converted to an `Either` using `catching`. Note that this only catches non-fatal 
exceptions!

```scala mdoc:compile-only
import ox.catching

val result: Either[Throwable, String] = catching(throw new RuntimeException("boom"))
```
