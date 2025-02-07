# Transforming channels 

## Transforming eagerly

Sources can be transformed by receiving values, manipulating them and sending to other channels - this provides the
highest flexibility and allows creating arbitrary channel topologies.

Some basic channel-transformaing operations are available as methods on `Source`. For example:

```scala
import ox.supervised
import ox.channels.{Channel, Source}

supervised:
  val c = Channel.rendezvous[String]
  val c2: Source[Int] = c.map(s => s.length())
```

The `.map` needs to be run within a scope, as it starts a new virtual thread (using `fork`), which:

* immediately starts receiving values from the given source
* applies the given function
* sends the result to the new channel

The new channel is returned to the user as the return value of `.map`.

To run multiple transformations within one virtual thread / fork, the `.transform` method is available:

```scala
import ox.supervised
import ox.channels.Source

supervised:
  Source.fromIterable(1 to 1000)
    .transform(_.filter(_ % 2 == 0).map(_ + 1).take(10)) // take the 10 first even numbers, incremented by 1
    .foreach(n => println(n.toString))
```

```{note}
For more advanced transformation options, use [flows](flows.md).
```

## Capacity of transformation stages

Most source and some flow transformation methods create new channels, on which the transformed values are produced. The capacity of
these channels by default is 16 (buffered). This can be overridden by providing `BufferCapacity` given, e.g.:

```scala
(v: Source[Int]).map(_ + 1)(using BufferCapacity(10))
```

## Discharging channels

Values of a source can be discharged using methods such as `.foreach`, `.toList`, `.pipeTo` or `.drain`:

```scala
import ox.supervised
import ox.channels.Source

supervised:
  val s = Source.fromValues(1, 2, 3)
  s.toList: List[Int] // List(1, 2, 3)
```

These methods are blocking, as they drain the channel until no more values are available (when the channel is done).

### Closed channels (done / error)

If the channel encounters an error, the discharging method will throws a `ChannelClosedException.Error`. Similarly as 
with `send` and `receive`, there's a `safe` variant for each discharing method, which returns a union type, e.g.:

```scala
import ox.supervised
import ox.channels.{ChannelClosed, Source}

supervised:
  val s = Source.fromValues(1, 2, 3)
  s.toList: List[Int] | ChannelClosed.Error // List(1, 2, 3)
```
