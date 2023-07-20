# Ox

Developer-friendly structured concurrency library for the JVM, based on:
* [Project Loom](https://openjdk.org/projects/loom/) (virtual threads)
* structured concurrency Java APIs ([JEP 428](https://openjdk.org/jeps/428)) 
* scoped values ([JEP 429](https://openjdk.org/jeps/429))
* [Go](https://golang.org)-like channels
* the [Scala](https://www.scala-lang.org) programming language

Requires JDK 20. Applications need the following JVM flags: `--enable-preview --add-modules jdk.incubator.concurrent`.

[sbt](https://www.scala-sbt.org) dependency:

```scala
"com.softwaremill.ox" %% "core" % "0.0.6"
```

Introductory articles:

* [Prototype Loom-based concurrency API for Scala](https://softwaremill.com/prototype-loom-based-concurrency-api-for-scala/) 
* [Go-like channels using project Loom and Scala](https://softwaremill.com/go-like-channels-using-project-loom-and-scala/)

# Community

If you'd have feedback, development ideas or critique, please head to our [community forum](https://softwaremill.community/c/ox/12)!

# API overview

## Run two computations in parallel

```scala
import ox.par

def computation1: Int =
  Thread.sleep(2000)
  1

def computation2: String =
  Thread.sleep(1000)
  "2"

val result: (Int, String) = par(computation1)(computation2)
// (1, "2")
```

If one of the computations fails, the other is interrupted, and `par` waits until both branches complete.

## Race two computations

```scala
import ox.raceSuccess

def computation1: Int =
  Thread.sleep(2000)
  1

def computation2: String =
  Thread.sleep(1000)
  2

val result: Int = raceSuccess(computation1)(computation2)
// 2
```

The loosing computation is interrupted using `Thread.interrupt`. `raceSuccess` waits until both branches finish; this
also applies to the loosing one, which might take a while to clean up after interruption.

### Error handling

* `raceSuccess` returns the first result, or re-throws the last exception
* `raceResult` returns the first result, or re-throws the first exception

## Timeout a computation

```scala
import ox.timeout
import scala.concurrent.duration.DurationInt

def computation: Int =
  Thread.sleep(2000)
  1

val result1: Try[Int] = Try(timeout(1.second)(computation)) // failure: TimeoutException
val result2: Try[Int] = Try(timeout(3.seconds)(computation)) // success: 1
```

## Fork & join threads

It's safest to use higher-level methods, such as `par` or `raceSuccess`, however this isn't always sufficient. For
these cases, threads can be started using the structured concurrency APIs described below.

The lifetime of the threads is defined by the structure of the code, and corresponds to the `scoped` block. Once
`scoped` exits, any threads that are still running are interrupted. Hence, it is guaranteed that all threads started 
within `scoped` will finish successfully, with an exception, or due to an interrupt.

```scala
import ox.{fork, scoped}

// same as `par`
scoped {
  val f1 = fork {
    Thread.sleep(2000)
    1
  }

  val f2 = fork {
    Thread.sleep(1000)
    2
  }

  (f1.join(), f2.join())
}
```

It is a compile-time error to use `fork` outside of a `scoped` block. Helper methods might require to be run within
a `scoped` block by requiring the `Ox` capability:

```scala
import ox.{fork, Fork, Ox, scoped}

def forkComputation(p: Int)(using Ox): Fork[Int] = fork {
  Thread.sleep(p * 1000)
  p + 1
}

scoped {
  val f1 = forkComputation(2)
  val f2 = forkComputation(4)
  (f1.join(), f2.join())
}
```

Scopes can be arbitrarily nested.

### Error handling

Any unhandled exceptions that are thrown in a `fork` block are propagated to the scope's main thread, by interrupting
it and re-throwing the exception there. Hence, any failed fork will cause the entire scope's computation to be 
interrupted, and unless interruptions are intercepted, all other forks will get interrupted as well.

On the other hand, `forkHold` doesn't propagate any exceptions but retains them. The result of the fork must be
explicitly inspected to discover, if the computation failed or succeeded, e.g. using the `Fork.join` method.

## Scoped values

Scoped value replace usages of `ThreadLocal` when using virtual threads and structural concurrency. They are useful to 
propagate auxiliary context, e.g. trace or correlation ids.

Values are bound structurally as well, e.g.:

```scala
import ox.{ForkLocal, fork, scoped}

val v = ForkLocal("a")
scoped {
  println(v.get()) // "a"
  fork {
    v.scopedWhere("x") {
      println(v.get()) // "x"
      fork {
        println(v.get()) // "x"
      }.join()
    }
  }.join()
  println(v.get()) // "a"
}
```

Scoped values propagate across nested scopes.

## Interruptions

When catching exceptions, care must be taken not to catch & fail to propagate an `InterruptedException`. Doing so will
prevent the scope cleanup mechanisms to make appropriate progress, as the scope won't finish until all started threads
complete.

A good solution is to catch only non-fatal exception using `NonFatal`, e.g.:

```scala
import ox.{forever, fork, scoped}

def processSingleItem(): Unit = ()

scoped {
  fork {
    forever {
      try processSingleItem()
      catch case NonFatal(e) => logger.error("Processing error", e)
    }
  }

  // do something else that keeps the scope busy
}
```

## Resources

### In-scope

Resources can be allocated within a scope. They will be released in reverse acquisition order, after the scope completes
(that is, after all forks started within finish). E.g.:

```scala
import ox.useScoped

case class MyResource(c: Int)

def acquire: MyResource = 
  println("acquiring ...")
  MyResource(5)
def release(resource: MyResource): Unit =
  println(s"releasing ${resource.c}...")

scoped {
  val resource1 = useInScope(acquire(10))(release)
  val resource2 = useInScope(acquire(20))(release)
  println(s"Using $resource1 ...")
  println(s"Using $resource2 ...")
}
```

### Scoped

Resources can also be used in a dedicated scope:

```scala
import ox.useScoped

case class MyResource(c: Int)

def acquire: MyResource = 
  println("acquiring ...")
  MyResource(5)
def release(resource: MyResource): Unit =
  println(s"releasing ${resource.c}...")

useScoped(acquire(10))(release) { resource =>
  println(s"Using $resource ...")
}
```

If the resource extends `AutoCloseable`, the `release` method doesn't need to be provided.

## Helper control flow methods

There are some helper methods which might be useful when writing forked code:

* `forever { ... }` repeatedly evaluates the given code block forever
* `repeatWhile { ... }` repeatedly evaluates the given code block, as long as it returns `true`
* `retry(times, sleep) { ... }` retries the given block up to the given number of times
* `uninterruptible { ... }` evaluates the given code block making sure it can't be interrupted

## Syntax

Extension-method syntax can be imported using `import ox.syntax.*`. This allows calling methods such as 
`.fork`, `.raceSuccessWith`, `.parWith`, `.forever`, `.useInScope` directly on code blocks / values.

## Channels basics

A channel is like a queue (data can be sent/received), but additionally channels support:

* completion (a source can be `done`)
* error propagation downstream
* receiving exactly one value from a number of channels

Creating a channel is a light-weight operation:

```scala
import ox.channels.*
val c = Channel[String]()
```

By default, channels are unbuffered, that is a sender and receiver must "meet" to exchange a value. Hence, `.send` 
always blocks, unless there's another thread waiting on a `.receive`. 

Buffered channels can be created by providing a non-zero capacity:

```scala
import ox.channels.*
val c = Channel[String](5)
```

Channels implement two trait: `Source` and `Sink`.

## Sinks

Data can be sent to a channel using `.send`. Once no more data items are available, completion can be signalled using
`.done`. If there's an error when producing data, this can be signalled using `.error`:

```scala
import ox. {fork, scoped}
import ox.channels.*

val c = Channel[String]()
scoped {
  fork {
    c.send("Hello")
    c.send("World")
    c.done()
  }

  // TODO: receive
}
```

`.send` is blocking, hence usually channels are shared across forks to communicate data between them.

## Sources

A source can be used to receive elements from a channel. The `.receive()` method can block, and the result might be
one of the following:

```scala
trait Source[+T]:
  def receive(): T | ChannelClosed

sealed trait ChannelClosed
object ChannelClosed:
  case class Error(reason: Option[Exception]) extends ChannelClosed
  case object Done extends ChannelClosed
```

That is, the result might be a value, or information that the channel is closed. A channel can be done or an error
might have occurred. Using an extension method provided by the `ox.channels.*` import, closed information can be thrown 
as an exception using `receive().orThrow: T`.

## Creating sources

Sources can be created using one of the many factory methods on the `Source` companion object, e.g.:

```scala
import ox.channels.Source
import scala.concurrent.duration.FiniteDuration

Source.fromValues(1, 2, 3)
Source.tick(1.second, "x")
Source.iterate(0)(_ + 1) // natural numbers
```

## Transforming sources

Sources can be transformed by receiving values, manipulating them and sending to other channels - this provides the
highest flexibility and allows creating arbitrary channel topologies.

However, there's a number of common operations that are built-in as methods on `Source`, which allow transforming the 
source. For example:

```scala
import ox.scoped
import ox.channels.{Channel, Source}

scoped {
  val c = Channel[String]()
  val c2: Source[Int] = c.map(s => s.length())
}
```

The `.map` needs to be run within a scope, as it starts a new virtual thread (using `fork`), which received values from
the given source, applies the given function and sends the result to the new channel, which is then returned to the 
user.

Some other available combinators include `.filter`, `.take`, `.zip(otherSource)`, `.merge(otherSource)` etc.

To run multiple transformations within one virtual thread / fork, the `.transform` method is available:

```scala
import ox.scoped
import ox.channels.{Channel, Source}

scoped {
  val c = Channel[Int]()
  fork {
    Source.iterate(0)(_ + 1) // natural numbers
      .transform(_.filter(_ % 2 == 0).map(_ + 1).take(10)) // take the 10 first even numbers, incremented by 1
      .foreach(n => println(n.toString))
  }
```

## Discharging channels

Values of a source can be terminated using methods such as `.foreach`, `.toList`, `.pipeTo` or `.drain`. These methods
are blocking, and hence don't need to be run within a scope:

```scala
import ox.channels.Source

val s = Source.fromValues(1, 2, 3)
s.toList // List(1, 2, 3)
```

## Selecting from channels

Channels are distinct from queues in that they support a `select` method, which takes a number of channel clauses, and 
block until at least one clause is satisfied. The other channels are left intact (no values are sent or received).

Channel clauses include:

* `channel.receiveClause` - to receive a value from the channel
* `channel.sendClause(value)` - to send a value to a channel
* `Default(value)` - to return the given value from the `select`, if no other clause can be immediately satisfied

### Receiving from exactly one channel

The most common use-case for `select` is to receive from exactly one channel. There's a dedicated `select` variant for
this use-case, which accepts a number of `Source`s, for which receive clauses are created. The signature for the 
two-source variant of this method is:

```scala
def select[T1, T2](source1: Source[T1], source2: Source[T2]): T1 | T2 | ChannelClosed
```

As an example, this can be used as follows:

```scala
import ox.Source
import scala.concurrent.duration.FiniteDuration

case object Tick
def consumer(strings: Source[String]): Nothing =
  scoped {
    val tick = Source.tick(1.second, Tick)

    @tailrec
    def doConsume(acc: Int): Nothing =
      select(strings, tick).orThrow match
        case Tick =>
          log.info(s"Characters received this second: $acc")
          doConsume(0)
        case s: String => doConsume(acc + s.length)

    doConsume(0)
  }
```

If any of the channels is in an error state, `select` returns with that error. If all channels are done, `selects` 
returns with a `Done` as well.

Selects are biased towards clauses/sources that appear first in the argument list. To achieve fairness, you might want
to randomize the ordering of the clauses/sources.

### Mixed receive and send clauses

The `select` method can also be used to send a value to exactly one channel, or with mixed receive and send clauses.
It is guaranteed that exactly one clause will be satisfied (either a value sent, or received from exactly one of the
channels).

For example:

```scala
val c = Channel[Int]()
val d = Channel[Int]()

select(c.sendClause(10), d.receiveClause)
```

The above will block until a value can be sent to `d` (as this is an unbuffered channel, for this to happen there must 
be a concurrently running `receive` call), or until a value can be received from `c`.

The type returned by the above invocation is:

```scala
c.Sent | d.Received | ChannelClosed
```

Note that the `Sent` and `Received` types are inner types of the `c` and `d` values. For different channels, the 
`Sent` / `Received` instances will have distinct classes, hence allowing distinguishing which clause has been satisfied.

Channel closed values can be inspected, or converted to an exception using `.orThrow`. 

The results of a `select` can be inspected using a pattern match:

```scala
val c = Channel[Int]()
val d = Channel[Int]()

select(c.sendClause(10), d.receiveClause).orThrow match
  case c.Sent()      => println("Sent to c")
  case d.Received(v) => println(s"Received from d: $v")
```

If there's a missing case, the compiler will warn you that the `match` is not exhaustive, and give you a hint as to
what is missing. Similarly, there will be a warning in case of an unneeded, extra match case.

### Default clauses

A default clause can be provided, which specifies the return value of the `select`, in case no other clause can be
immediately satisfied. The clause can be created with `Default`, and in case the value is used, it is returned wrapped
in `DefaultResult`. For example:

```scala
val c = Channel[Int]()

select(c.receiveClause, Default(5)).orThrow match
  case c.Received(v)    => println(s"Received from d: $v")
  case DefaultResult(v) => println(s"No value available in c, using default: $v")
```

There can be at most one default clause in a `select` invocation.

## Error propagation

Errors are only propagated downstream, ultimately reaching the point where the source is discharged, leading to an
exception being thrown there.

Won't this design cause upstream channels / sources to operate despite the consumer being gone (because of the 
exception)?

No: the exception should cause the containing scope to finish, interrupting any forks that are operating in the 
background. Any unused channels can then be garbage-collected.

The role of the exception handler is then to re-create the entire processing pipeline, or escalate the error further.

## Backpressure

Channels are back-pressured, as the `.send` operation is blocking until there's a receiver thread available, or if 
there's enough space in the buffer. The processing space is bound by the total size of channel buffers.

# Development

To compile and test, run:

```
sbt compile
sbt test
```
