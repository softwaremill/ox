# Ox

Safe direct-style concurrency and resiliency for Scala on the JVM, based on:

* [Project Loom](https://openjdk.org/projects/loom/) (virtual threads)
* structured concurrency Java APIs ([JEP 428](https://openjdk.org/jeps/428)) 
* scoped values ([JEP 429](https://openjdk.org/jeps/429))
* fast, scalable [Go](https://golang.org)-like channels using [jox](https://github.com/softwaremill/jox)
* the [Scala 3](https://www.scala-lang.org) programming language

Requires JDK 21.

[sbt](https://www.scala-sbt.org) dependency:

```scala
"com.softwaremill.ox" %% "core" % "0.0.18"
```

Introductory articles:

* [Prototype Loom-based concurrency API for Scala](https://softwaremill.com/prototype-loom-based-concurrency-api-for-scala/) 
* [Go-like channels using project Loom and Scala](https://softwaremill.com/go-like-channels-using-project-loom-and-scala/)

# Scope of the project

The areas that we'd like to cover with Ox are:

* concurrency: developer-friendly structured concurrency, high-level concurrency operators, safe low-level primitives, communication between concurrently running computations
* error management: retries, timeouts, a safe approach to error propagation, safe resource management
* scheduling & timers
* resiliency: circuit breakers, bulkheads, rate limiters, backpressure

All of the above should allow for observability of the orchestrated business logic. We aim to enable writing simple, expression-oriented code in functional style. We'd like to keep the syntax overhead to a minimum, preserving developer-friendly stack traces, and without compromising performance.

Some of the above are already addressed in the API, some are coming up in the future. We'd love your help in shaping the project!

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

## Parallelize collection operations

### mapPar

```scala
import ox.syntax.mapPar

val input: List[Int] = List(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)

val result: List[Int] = input.mapPar(4)(_ + 1)
// (2, 3, 4, 5, 6, 7, 8, 9, 10)
```

If any transformation fails, others are interrupted and `mapPar` rethrows exception that was
thrown by the transformation. Parallelism
limits how many concurrent forks are going to process the collection.

### foreachPar

```scala
import ox.syntax.foreachPar

val input: List[Int] = List(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)

input.foreachPar(4)(i => println())
// Prints each element of the list, might be in any order
```

Similar to `mapPar` but doesn't return anything.

### filterPar

```scala
import ox.syntax.filterPar

val input: List[Int] = List(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)
  
val result:List[Int] = input.filterPar(4)(_ % 2 == 0)
// (2, 4, 6, 8, 10)
``` 

Filters collection in parallel using provided predicate. If any predicate fails, rethrows the exception
and other forks calculating predicates are interrupted.

### collectPar

```scala
import ox.syntax.collectPar

val input: List[Int] = List(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)
  
val result: List[Int] = input.collectPar(4) {
  case i if i % 2 == 0 => i + 1
} 
// (3, 5, 7, 9, 11)
```

Similar to `mapPar` but only applies transformation to elements for which
the partial function is defined. Other elements are skipped.

## Race two computations

```scala
import ox.raceSuccess

def computation1: Int =
  Thread.sleep(2000)
  1

def computation2: Int =
  Thread.sleep(1000)
  2

val result: Int = raceSuccess(computation1)(computation2)
// 2
```

The losing computation is interrupted using `Thread.interrupt`. `raceSuccess` waits until both branches finish; this
also applies to the losing one, which might take a while to clean up after interruption.

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

A variant, `timeoutOption`, doesn't throw a `TimeoutException` on timeout, but returns `None` instead.

## Fork & join threads

It's safest to use higher-level methods, such as `par` or `raceSuccess`, however this isn't always sufficient. For
these cases, threads can be started using the structured concurrency APIs described below.

Forks (new threads) can only be started with a **scope**. Such a scope is defined using the `supervised` or `scoped`
methods. 

The lifetime of the forks is defined by the structure of the code, and corresponds to the enclosing `supervised` or 
`scoped` block. Once the code block passed to the scope completes, any forks that are still running are interrupted. 
The whole block will complete only once all forks have completed (successfully, or with an exception). 

Hence, it is guaranteed that all forks started within `supervised` or `scoped` will finish successfully, with an 
exception, or due to an interrupt.

```scala
import ox.{forkUser, supervised}

// same as `par`
supervised {
  val f1 = forkUser {
    Thread.sleep(2000)
    1
  }

  val f2 = forkUser {
    Thread.sleep(1000)
    2
  }

  (f1.join(), f2.join())
}
```

It is a compile-time error to use `fork`/`forkUser` outside of a `supervised` or `scoped` block. Helper methods might 
require to be run within a scope by requiring the `Ox` capability:

```scala
import ox.{forkUser, Fork, Ox, supervised}

def forkComputation(p: Int)(using Ox): Fork[Int] = forkUser {
  Thread.sleep(p * 1000)
  p + 1
}

supervised {
  val f1 = forkComputation(2)
  val f2 = forkComputation(4)
  (f1.join(), f2.join())
}
```

Scopes can be arbitrarily nested.

### Supervision

The default scope, created with `supervised`, watches over the forks that are started within. Any forks started with
`fork` and `forkUser` are by default supervised.

This means that the scope will end only when either:

* all (user, supervised) forks, including the main body passed to `supervised`, succeed
* or any (supervised) fork, including the main body passed to `supervised`, fails

Hence an exception in any of the forks will cause the whole scope to end. Ending the scope means that all running forks
are cancelled (using interruption). Once all forks complete, the exception is propagated further, that is re-thrown by 
the `supervised` method invocation:

```scala
import ox.{fork, forkUser, Fork, Ox, supervised}

supervised {
  forkUser {
    Thread.sleep(1000)
    println("Hello!")
  }
  fork {
    Thread.sleep(500)
    throw new RuntimeException("boom!")
  }
}

// doesn't print "Hello", instead throws "boom!"
```

### User, daemon and unsupervised forks

In supervised scoped, forks created using `fork` behave as daemon threads. That is, their failure ends the scope, but
the scope will also end once the main body and all user forks succeed, regardless if the (daemon) fork is still running.

Alternatively, a user fork can be created using `forkUser`. Such a fork is required to complete successfully, in order
for the scope to end successfully. Hence when the main body of the scope completes, the scope will wait until all user
forks have completed as well.

Finally, entirely unsupervised forks can be ran using `forkUnsupervised`.

### Unsupervised scopes

An unsupervised scope can be created using `scoped`. Any forks started within are unsupervised. This is considered an
advanced feature, and should be used with caution.

Such a scope ends, once the code block passed to `scoped` completes. Then, all running forks are cancelled. Still, the
scope completes (that is, the `scoped` block returns) only once all forks have completed.

Fork failures aren't handled in any special way, and can be inspected using the `Fork.join()` method.

### Cancelling forks

By default, forks are not cancellable by the user. Instead, all outstanding forks are cancelled (interrupted) when the
enclosing scope ends.

If needed, a cancellable fork can be created using `forkCancellable`. However, such an operation is more expensive, as
it involves creating a nested scope and two virtual threads, instead of one. 

The `CancellableFork` trait exposes the `.cancel` method, which interrupts the fork and awaits its completion. 
Alternatively, `.cancelNow` returns immediately. In any case, the enclosing scope will only complete once all forks have
completed.

### Error handling

In supervised mode, if a fork fails with an exception, the enclosing scope will end.

Moreover, if a fork fails with an exception, the `Fork.join` method will throw that exception. 

In unsupervised mode, if there's no join and the fork fails, the exception might go unnoticed.

## Scoped values

Scoped value replace usages of `ThreadLocal` when using virtual threads and structural concurrency. They are useful to 
propagate auxiliary context, e.g. trace or correlation ids.

Values are bound structurally as well, e.g.:

```scala
import ox.{ForkLocal, fork, supervised}

val v = ForkLocal("a")
supervised {
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
import ox.{forever, fork, supervised}

def processSingleItem(): Unit = ()

supervised {
  fork {
    forever {
      try processSingleItem()
      catch case NonFatal(e) => logger.error("Processing error", e)
    }
  }

  // do something else
}
```

## Resources

### In-scope

Resources can be allocated within a scope. They will be released in reverse acquisition order, after the scope completes
(that is, after all forks started within finish). E.g.:

```scala
import ox.{supervised, useInScope}

case class MyResource(c: Int)

def acquire(c: Int): MyResource =
  println(s"acquiring $c ...")
  MyResource(c)

def release(resource: MyResource): Unit =
  println(s"releasing ${resource.c} ...")

supervised {
  val resource1 = useInScope(acquire(10))(release)
  val resource2 = useInScope(acquire(20))(release)
  println(s"Using $resource1 ...")
  println(s"Using $resource2 ...")
}
```

### Supervised / scoped

Resources can also be used in a dedicated scope:

```scala
import ox.useSupervised

case class MyResource(c: Int)

def acquire(c: Int): MyResource =
  println(s"acquiring $c ...")
  MyResource(c)

def release(resource: MyResource): Unit =
  println(s"releasing ${resource.c} ...")

useSupervised(acquire(10))(release) { resource =>
  println(s"Using $resource ...")
}
```

If the resource extends `AutoCloseable`, the `release` method doesn't need to be provided.

## Helper control flow methods

There are some helper methods which might be useful when writing forked code:

* `forever { ... }` repeatedly evaluates the given code block forever
* `repeatWhile { ... }` repeatedly evaluates the given code block, as long as it returns `true`
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

Unbounded channels can be created using `Channel.unlimited[T]`.

Channels implement two traits: `Source` and `Sink`.

## Sinks

Data can be sent to a channel using `.send`. Once no more data items are available, completion can be signalled using
`.done`. If there's an error when producing data, this can be signalled using `.error`:

```scala
import ox. {fork, supervised}
import ox.channels.*

val c = Channel[String]()
supervised {
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

Each such source creates a daemon fork, which takes care of sending the elements to the channel, once capacity is
available.

## Transforming sources (eagerly)

Sources can be transformed by receiving values, manipulating them and sending to other channels - this provides the
highest flexibility and allows creating arbitrary channel topologies.

However, there's a number of common operations that are built-in as methods on `Source`, which allow transforming the 
source. For example:

```scala
import ox.supervised
import ox.channels.{Channel, Source}

supervised {
  val c = Channel[String]()
  val c2: Source[Int] = c.map(s => s.length())
}
```

The `.map` needs to be run within a scope, as it starts a new virtual thread (using `forkDaemon`), which:

* immediately starts receiving values from the given source
* applies the given function 
* sends the result to the new channel

The new channel is returned to the user as the return value of `.map`.

Some other available combinators include `.filter`, `.take`, `.zip(otherSource)`, `.merge(otherSource)` etc.

To run multiple transformations within one virtual thread / fork, the `.transform` method is available:

```scala
import ox.supervised
import ox.channels.Source

supervised {
  Source.iterate(0)(_ + 1) // natural numbers
    .transform(_.filter(_ % 2 == 0).map(_ + 1).take(10)) // take the 10 first even numbers, incremented by 1
    .foreach(n => println(n.toString))
}
```

### Capacity of transformation stages

Most source transformation methods create new channels, on which the transformed values are produced. The capacity of
these channels by default is 0 (unbuffered). This can be overridden by providing `StageCapacity` given, e.g.:

```scala
(v: Source[Int]).map(_ + 1)(using StageCapacity(10))
```

## Transforming sources (lazily)

A limited number of transformations can be applied to a source without creating a new channel and a new fork, which
computes the transformation. These include: `.mapAsView`, `.filterAsView` and `.collectAsView`.

For example:

```scala
import ox.channels.{Channel, Source}

val c = Channel[String]()
val c2: Source[Int] = c.mapAsView(s => s.length())
```

The mapping function (`s => s.length()`) will only be invoked when the source is consumed (using `.receive()` 
or `select`), on the calling thread. This is in contrast to `.map`, where the mapping function is invoked on a separate 
fork. 

Hence, creating views doesn't need to be run within a scope, and creating the view itself doesn't consume any elements 
from the source on which it is run. 

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

The most common use-case for `select` is to receive exactly one value from a number of channels. There's a dedicated 
`select` variant for this use-case, which accepts a number of `Source`s, for which receive clauses are created. The 
signature for the two-source variant of this method is:

```scala
def select[T1, T2](source1: Source[T1], source2: Source[T2]): T1 | T2 | ChannelClosed
```

As an example, this can be used as follows:

```scala
import ox.{Source, supervised}
import ox.channels.*
import scala.concurrent.duration.FiniteDuration

case object Tick
def consumer(strings: Source[String]): Nothing =
  supervised {
    val tick = Source.tick(1.second, Tick)

    @tailrec
    def doConsume(acc: Int): Nothing =
      select(tick, strings).orThrow match
        case Tick =>
          log.info(s"Characters received this second: $acc")
          doConsume(0)
        case s: String => doConsume(acc + s.length)

    doConsume(0)
  }
```

Selects are biased towards clauses/sources that appear first in the argument list. To achieve fairness, you might want
to randomize the ordering of the clauses/sources.

### Mixed receive and send clauses

The `select` method can also be used to send a value to exactly one channel, or with mixed receive and send clauses.
It is guaranteed that exactly one clause will be satisfied (either a value sent, or received from exactly one of the
channels).

For example:

```scala
import ox.channels.Channel

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
import ox.channels.*

val c = Channel[Int]()
val d = Channel[Int]()

select(c.sendClause(10), d.receiveClause).orThrow match
  case c.Sent()      => println("Sent to c")
  case d.Received(v) => println(s"Received from d: $v")
```

If there's a missing case, the compiler will warn you that the `match` is not exhaustive, and give you a hint as to
what is missing. Similarly, there will be a warning in case of an unneeded, extra match case.

### Closed channels (done / error)

If any of the channels is, or becomes, closed (in an error state / done), `select` returns with that error / done state.

It is possible to inspect which channel is in a closed state by using the `.isClosedForSend` and `.isClosedForReceive` 
methods (plus detailed variants).

### Default clauses

A default clause can be provided, which specifies the return value of the `select`, in case no other clause can be
immediately satisfied. The clause can be created with `Default`, and in case the value is used, it is returned wrapped
in `DefaultResult`. For example:

```scala
import ox.channels.*

val c = Channel[Int]()

select(c.receiveClause, Default(5)).orThrow match
  case c.Received(v)    => println(s"Received from d: $v")
  case DefaultResult(v) => println(s"No value available in c, using default: $v")
```

There can be at most one default clause in a `select` invocation.

## Error propagation

Errors are only propagated downstream, ultimately reaching the point where the source is discharged, leading to an
exception being thrown there.

The approach we decided to take (only propagating errors downstream) is one of the two possible designs - 
with the other being re-throwing an exception when it's encountered.
Please see [the respective ADR](doc/adr/0001-error-propagation-in-channels.md) for a discussion.

## Backpressure

Channels are back-pressured, as the `.send` operation is blocking until there's a receiver thread available, or if 
there's enough space in the buffer. The processing space is bound by the total size of channel buffers.

## Retries

The retries mechanism allows to retry a failing operation according to a given policy (e.g. retry 3 times with a 100ms delay between attempts). See the [full docs](doc/retries.md) for details.

## Kafka sources & drains

Dependency:

```scala
"com.softwaremill.ox" %% "kafka" % "0.0.18"
```

`Source`s which read from a Kafka topic, mapping stages and drains which publish to Kafka topics are available through 
the `KafkaSource`, `KafkaStage` and `KafkaDrain` objects. In all cases either a manually constructed instance of a 
`KafkaProducer` / `KafkaConsumer` is needed, or `ProducerSettings` / `ConsumerSetttings` need to be provided with the 
bootstrap servers, consumer group id, key / value serializers, etc.

To read from a Kafka topic, use:

```scala
import ox.channel.ChannelClosed
import ox.kafka.{ConsumerSettings, KafkaSource}
import ox.kafka.ConsumerSettings.AutoOffsetReset.Earliest
import ox.supervised
import org.apache.kafka.clients.consumer.ConsumerRecord

supervised {
  val settings = ConsumerSettings.default("my_group").bootstrapServers("localhost:9092").autoOffsetReset(Earliest)
  val source = KafkaSource.subscribe(settings, topic)

  source.receive(): ConsumerRecord[String, String] | ChannelClosed
}
```

To publish data to a Kafka topic:

```scala
import ox.channel.Source
import ox.kafka.{ProducerSettings, KafkaSink}
import ox.supervised
import org.apache.kafka.clients.producer.ProducerRecord

supervised {
  val settings = ProducerSettings.default.bootstrapServers("localhost:9092")
  Source
    .fromIterable(List("a", "b", "c"))
    .mapAsView(msg => ProducerRecord[String, String]("my_topic", msg))
    .applied(KafkaDrain.publish(settings))
}
```

To publish data and commit offsets of messages, basing on which the published data is computed:

```scala
import ox.kafka.{KafkaSink, KafkaSource, ProducerSettings, SendPacket}
import ox.supervised
import org.apache.kafka.clients.producer.ProducerRecord

supervised {
  val consumerSettings = ConsumerSettings.default("my_group").bootstrapServers("localhost:9092").autoOffsetReset(Earliest)
  val producerSettings = ProducerSettings.default.bootstrapServers("localhost:9092")

  KafkaSource
    .subscribe(consumerSettings, sourceTopic)
    .map(in => (in.value().toLong * 2, in))
    .map((value, original) => SendPacket(ProducerRecord[String, String](destTopic, value.toString), original))
    .applied(KafkaDrain.publishAndCommit(consumerSettings, producerSettings))
}
```

The offsets are committed every second in a background process.

To publish data as a mapping stage:

```scala
import ox.channel.Source
import ox.kafka.{ProducerSettings, KafkaSink}
import ox.kafka.KafkaStage.*
import ox.supervised
import org.apache.kafka.clients.producer.{ProducerRecord, RecordMetadata}

supervised {
  val settings = ProducerSettings.default.bootstrapServers("localhost:9092")
  val metadatas: Source[RecordMetadata] = Source
    .fromIterable(List("a", "b", "c"))
    .mapAsView(msg => ProducerRecord[String, String]("my_topic", msg))
    .mapPublish(settings)
  
  // process the metadatas source further
}
```

# Dictionary

How we use various terms throughout the codebase (or at least try to):

* **concurrency scope**: either `supervised` (default) or `scoped` ("advanced")
* a scope **ends**: when unsupervised, the main body is entirely evaluated; when supervised, all user (non-daemon), 
  supervised forks complete successfully, or at least one supervised fork fails. When the scope ends, all running
  forks are interrupted
* scope **completes**, once all forks complete and finalizers are run. In other words, the `supervised` or `scoped`
  method returns.
* forks are **started**, and then they are **running**
* forks **complete**: either a fork **succeeds**, or a fork **fails** with an exception
* **cancellation** (`Fork.cancel()`) interrupts the fork and waits until it completes
* scope **body**: the code block passed to a `supervised` or `scoped` method

# Performance

Some performance tests have been done around channels, see:

* [Limits of Loom's performance](https://softwaremill.com/limits-of-looms-performance/)
* [Go-like selects using jox channels in Java](https://softwaremill.com/go-like-selects-using-jox-channels-in-java/)

# Development

To compile and test, run:

```
sbt compile
sbt test
```

## Project sponsor

We offer commercial development services. [Contact us](https://softwaremill.com) to learn more about us!

## Copyright

Copyright (C) 2023-2024 SoftwareMill [https://softwaremill.com](https://softwaremill.com).
