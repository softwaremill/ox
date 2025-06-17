# Selecting from channels

Channels are distinct from queues in that they support a `select` method, which takes a number of channel clauses, and
blocks until at least one clause is satisfied. The other channels are left intact (no values are sent or received).

Channel clauses include:

* `Source.receiveClause` - to receive a value from the channel
* `Sink.sendClause(value)` - to send a value to a channel
* `Default(value)` - to return the given value from the `select`, if no other clause can be immediately satisfied

## Receiving exactly one value from multiple channels

The most common use-case for `select` is to receive exactly one value from a number of channels. There's a dedicated
`select` variant for this use-case, which accepts a number of `Source`s, for which receive clauses are created. The
signature for the two-source variant of this method is:

```scala
def select[T1, T2](source1: Source[T1], source2: Source[T2]): T1 | T2
```

As an example, this can be used as follows:

```scala
import ox.supervised
import ox.channels.*
import ox.flow.Flow

import scala.annotation.tailrec
import scala.concurrent.duration.*

case object Tick
def consumer(strings: Source[String]): Nothing =
  supervised {
    val tick = Flow.tick(1.second, Tick).runToChannel()

    @tailrec
    def doConsume(acc: Int): Nothing =
      select(tick, strings) match
        case Tick =>
          println(s"Characters received this second: $acc")
          doConsume(0)
        case s: String => doConsume(acc + s.length)

    doConsume(0)
  }
```

Selects are biased towards clauses/sources that appear first in the argument list. To achieve fairness, you might want
to randomize the ordering of the clauses/sources.

## Mixed receive and send clauses

The `select` method can also be used to send a value to exactly one channel, or with mixed receive and send clauses.
It is guaranteed that exactly one clause will be satisfied (either a value sent, or received from exactly one of the
channels).

For example:

```scala
import ox.channels.{Channel, select}

val c = Channel.rendezvous[Int]
val d = Channel.rendezvous[Int]

select(c.sendClause(10), d.receiveClause)
```

The above will block until a value can be sent to `d` (as this is an unbuffered channel, for this to happen there must
be a concurrently running `receive` call), or until a value can be received from `c`.

The type returned by the above invocation is:

```scala
c.Sent | d.Received
```

Note that the `Sent` and `Received` types are inner types of the `c` and `d` values. For different channels, the
`Sent` / `Received` instances will have distinct classes, hence allowing distinguishing which clause has been satisfied.

Channel closed values can be inspected, or converted to an exception using `.orThrow`.

The results of a `select` can be inspected using a pattern match:

```scala
import ox.channels.*

val c = Channel.rendezvous[Int]
val d = Channel.rendezvous[Int]

select(c.sendClause(10), d.receiveClause) match
  case c.Sent()      => println("Sent to c")
  case d.Received(v) => println(s"Received from d: $v")
```

If there's a missing case, the compiler will warn you that the `match` is not exhaustive, and give you a hint as to
what is missing. Similarly, there will be a warning in case of an unneeded, extra match case.

## Closed channels (done / error)

If any of the channels is, or becomes, closed (in an error state / done), `select` throws a `ChannelClosedException` 
with the details of the error / done state. Similarly as with `send` and `receive`, there's a `safe` variant for each
`select` method overload, which returns a union type, e.g.:

```scala
def selectSafe[T1, T2](source1: Source[T1], source2: Source[T2]): T1 | T2 | ChannelClosed
```

It is possible to inspect which channel is in a closed state by using the `.isClosedForSend` and `.isClosedForReceive`
methods (plus detailed variants).

## Default clauses

A default clause can be provided, which specifies the return value of the `select`, in case no other clause can be
immediately satisfied. The clause can be created with `Default`, and in case the value is used, it is returned wrapped
in `DefaultResult`. For example:

```scala
import ox.channels.*

val c = Channel.rendezvous[Int]

select(c.receiveClause, Default(5)) match
  case c.Received(v)    => println(s"Received from d: $v")
  case DefaultResult(v) => println(s"No value available in c, using default: $v")
```

There can be at most one default clause in a `select` invocation.

## Select with timeout

For scenarios where you want to wait for a channel operation to complete, but only for a limited time, Ox provides 
two timeout-enabled select variants: `selectOrClosedWithin` and `selectWithin`.

### selectOrClosedWithin

`selectOrClosedWithin` allows you to specify a timeout value that will be returned if no clauses can complete within 
the given duration:

```scala
import ox.channels.*
import ox.supervised
import scala.concurrent.duration.*

supervised:
  val c1 = Channel.rendezvous[Int]
  val c2 = Channel.rendezvous[String]
  
  // Returns "timeout" if no clause completes within 100ms
  val result = selectOrClosedWithin(100.millis, "timeout")(c1.receiveClause, c2.receiveClause)
  
  result match
    case c1.Received(value) => println(s"Received from c1: $value")
    case c2.Received(value) => println(s"Received from c2: $value") 
    case "timeout"          => println("Operation timed out")
    case closed: ChannelClosed => println(s"Channel closed: $closed")
```

You can also use `selectOrClosedWithin` directly with sources:

```scala
import ox.channels.*
import ox.supervised
import scala.concurrent.duration.*

supervised:
  val s1 = Channel.rendezvous[Int] 
  val s2 = Channel.rendezvous[String]
  
  // Returns -1 if no source has a value within 50ms
  val result = selectOrClosedWithin(50.millis, -1)(s1, s2)
  
  result match
    case value: Int if value == -1 => println("Timeout occurred")
    case value: Int                => println(s"Received int: $value")
    case value: String             => println(s"Received string: $value")
    case closed: ChannelClosed     => println(s"Channel closed: $closed")
```

### selectWithin

`selectWithin` is similar to `selectOrClosedWithin`, but instead of returning a timeout value, it throws a 
`TimeoutException` when the timeout is reached:

```scala
import ox.channels.*
import ox.supervised
import scala.concurrent.duration.*
import scala.concurrent.TimeoutException

supervised:
  val c1 = Channel.rendezvous[Int]
  val c2 = Channel.rendezvous[String]
  
  try
    val result = selectWithin(100.millis)(c1.receiveClause, c2.receiveClause)
    result match
      case c1.Received(value) => println(s"Received from c1: $value")
      case c2.Received(value) => println(s"Received from c2: $value")
  catch
    case _: TimeoutException => println("Operation timed out")
```

Similarly with sources:

```scala
import ox.channels.*
import ox.supervised
import scala.concurrent.duration.*
import scala.concurrent.TimeoutException

supervised:
  val s1 = Channel.rendezvous[Int]
  val s2 = Channel.rendezvous[String] 
  
  try
    val result = selectWithin(50.millis)(s1, s2)
    println(s"Received: $result")
  catch
    case _: TimeoutException => println("No data available within timeout")
```

### When to use which variant

- Use `selectOrClosedWithin` when you want to provide a default value or handle timeouts as part of your normal flow
- Use `selectWithin` when you want timeout to be treated as an exceptional condition that should interrupt normal flow

Both variants support all the same overloads as regular `select`: single clauses, multiple clauses (up to 5), and 
sequences of clauses or sources.
