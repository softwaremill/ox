# Selecting from channels

Channels are distinct from queues in that they support a `select` method, which takes a number of channel clauses, and
block until at least one clause is satisfied. The other channels are left intact (no values are sent or received).

Channel clauses include:

* `channel.receiveClause` - to receive a value from the channel
* `channel.sendClause(value)` - to send a value to a channel
* `Default(value)` - to return the given value from the `select`, if no other clause can be immediately satisfied

## Receiving from exactly one channel

The most common use-case for `select` is to receive exactly one value from a number of channels. There's a dedicated
`select` variant for this use-case, which accepts a number of `Source`s, for which receive clauses are created. The
signature for the two-source variant of this method is:

```scala
def select[T1, T2](source1: Source[T1], source2: Source[T2]): T1 | T2 | ChannelClosed
```

As an example, this can be used as follows:

```scala mdoc:compile-only
import ox.supervised
import ox.channels.*

import scala.annotation.tailrec
import scala.concurrent.duration.*

case object Tick
def consumer(strings: Source[String]): Nothing =
  supervised {
    val tick = Source.tick(1.second, Tick)

    @tailrec
    def doConsume(acc: Int): Nothing =
      select(tick, strings).orThrow match
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

```scala mdoc:compile-only
import ox.channels.{Channel, select}

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

```scala mdoc:compile-only
import ox.channels.*

val c = Channel[Int]()
val d = Channel[Int]()

select(c.sendClause(10), d.receiveClause).orThrow match
  case c.Sent()      => println("Sent to c")
  case d.Received(v) => println(s"Received from d: $v")
```

If there's a missing case, the compiler will warn you that the `match` is not exhaustive, and give you a hint as to
what is missing. Similarly, there will be a warning in case of an unneeded, extra match case.

## Closed channels (done / error)

If any of the channels is, or becomes, closed (in an error state / done), `select` returns with that error / done state.

It is possible to inspect which channel is in a closed state by using the `.isClosedForSend` and `.isClosedForReceive`
methods (plus detailed variants).

## Default clauses

A default clause can be provided, which specifies the return value of the `select`, in case no other clause can be
immediately satisfied. The clause can be created with `Default`, and in case the value is used, it is returned wrapped
in `DefaultResult`. For example:

```scala mdoc:compile-only
import ox.channels.*

val c = Channel[Int]()

select(c.receiveClause, Default(5)).orThrow match
  case c.Received(v)    => println(s"Received from d: $v")
  case DefaultResult(v) => println(s"No value available in c, using default: $v")
```

There can be at most one default clause in a `select` invocation.
