# Creating sources

Sources can be created using one of the many factory methods on the `Source` companion object, e.g.:

```scala mdoc:compile-only
import ox.supervised
import ox.channels.Source

import scala.concurrent.duration.*

supervised {
  Source.fromValues(1, 2, 3)
  Source.tick(1.second, "x")
  Source.iterate(0)(_ + 1) // natural numbers
}
```

Each such source creates a daemon fork, which takes care of sending the elements to the channel, once capacity is
available.
