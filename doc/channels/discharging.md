# Discharging channels

Values of a source can be terminated using methods such as `.foreach`, `.toList`, `.pipeTo` or `.drain`. These methods
are blocking, and hence don't need to be run within a scope:

```scala
import ox.channels.Source

val s = Source.fromValues(1, 2, 3)
s.toList // List(1, 2, 3)
```
