# Discharging channels

Values of a source can be terminated using methods such as `.foreach`, `.toList`, `.pipeTo` or `.drain`. These methods
are blocking, and hence don't need to be run within a scope:

```scala mdoc:compile-only
import ox.supervised
import ox.channels.Source

supervised {
  val s = Source.fromValues(1, 2, 3)
  s.toList // List(1, 2, 3)
}
```
