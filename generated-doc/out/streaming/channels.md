# Channels

A channel is like a queue (values can be sent/received), but additionally channels support:

* completion (a source can be `done`)
* downstream error propagation
* `select`ing exactly one channel clause to complete, where clauses include send and receive operations

Creating a channel is a light-weight operation:

```scala
import ox.channels.*
val c = Channel.bufferedDefault[String]
```

This uses the default buffer size (16). It's also possible to create channels with other buffer sizes, as well as 
rendezvous or unlimited channels:

```scala
import ox.channels.*
val c1 = Channel.rendezvous[String]
val c2 = Channel.buffered[String](5)
val c3 = Channel.unlimited[String]
```

In rendezvous channels, a sender and receiver must "meet" to exchange a value. Hence, `.send` always blocks, unless 
there's another thread waiting on a `.receive`. In buffered channels, `.send` only blocks when the buffer is full. 
In an unlimited channel, sending never blocks.

Channels implement two traits: `Source` and `Sink`.

## Sinks

Data can be sent to a channel using `Sink.send`. Once no more data items are available, completion can be signalled 
downstream using `Sink.done`. If there's an error when producing data, this can be signalled using `Sink.error`:

```scala
import ox.{fork, supervised}
import ox.channels.*

val c = Channel.rendezvous[String]
supervised:
  fork:
    c.send("Hello")
    c.send("World")
    c.done()

  // TODO: receive
```

`.send` blocks the thread, hence usually channels are shared across forks to communicate data between them. As Ox is
designed to work with Java 21+ and Virtual Threads, blocking is a cheap operation that might be done frequently.

## Sources

A source can be used to receive elements from a channel. 

```scala
trait Source[+T]:
  def receive(): T
```

Same as `.send`, the `.receive` method might block the current thread.

### Creating sources

Sources can be created by instantiating a new channel. There are also some basic factory methods on the `Source` companion object. Finally, a [flow](flows.md) can be run to a channel if needed, e.g.:

```scala
import ox.supervised
import ox.channels.Source
import ox.flow.Flow

import scala.concurrent.duration.*

supervised:
  Source.fromValues(1, 2, 3)
  Flow.tick(1.second, "x").runToChannel()
  Flow.iterate(0)(_ + 1).runToChannel() // natural numbers
```

Typically, for each source created as shown above a fork is started, which sends the elements to the channel when capacity is available. If the enclosing `supervised` scope ends, each such fork is cancelled.

## Handling closed channels

By default, `Sink.send` and `Source.receive` methods will throw a `ChannelClosedException`, if the channel is already
closed:

```scala
enum ChannelClosedException(cause: Option[Throwable]) extends Exception(cause.orNull):
  case Error(cause: Throwable) extends ChannelClosedException(Some(cause))
  case Done() extends ChannelClosedException(None)
```

Alternatively, you can call `Sink.sendSafe` or `Source.receiveSafe`, which return union types:

```scala
trait Source[+T]:
  def receive(): T
  def receiveSafe(): T | ChannelClosed

trait Sink[-T]:
  def send(value: T): Unit
  def sendSafe(value: T): Unit | ChannelClosed
  def done(): Unit
  def doneSafe(): Unit | ChannelClosed
  def error(cause: Throwable): Unit
  def errorSafe(cause: Throwable): Unit | ChannelClosed

sealed trait ChannelClosed
object ChannelClosed:
  case class Error(reason: Option[Exception]) extends ChannelClosed
  case object Done extends ChannelClosed
```

That is, the result of a `safe` operation might be a value, or information that the channel is closed.

Using extensions methods from `ChannelClosedUnion` it's possible to convert such union types to `Either`s, `Try`s or
exceptions, as well as `map` over such results.
