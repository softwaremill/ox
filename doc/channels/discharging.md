# Discharging channels

Values of a source can be discharged using methods such as `.foreach`, `.toList`, `.pipeTo` or `.drain`:

```scala mdoc:compile-only
import ox.supervised
import ox.channels.Source

supervised {
  val s = Source.fromValues(1, 2, 3)
  s.toList: List[Int] // List(1, 2, 3)
}
```

These methods are blocking, as they drain the channel until no more values are available (when the channel is done).

## Closed channels (done / error)

If the channel encounters an error, the discharging method will throws a `ChannelClosedException.Error`. Similarly as 
with `send` and `receive`, there's a `safe` variant for each discharing method, which returns a union type, e.g.:

```scala mdoc:compile-only
import ox.supervised
import ox.channels.{ChannelClosed, Source}

supervised {
  val s = Source.fromValues(1, 2, 3)
  s.toList: List[Int] | ChannelClosed.Error // List(1, 2, 3)
}
```
