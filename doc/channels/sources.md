# Sources

A source can be used to receive elements from a channel. 

```scala mdoc:compile-only
trait Source[+T]:
  def receive(): T
```

Same as `.send`, the `.receive` method might block the current thread.

## Creating sources

Sources can be created by instantiating a new channel, or using one of the many factory methods on the `Source` 
companion object, e.g.:

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

Each such source creates a fork, which takes care of sending the elements to the channel, once capacity is available.
If the enclosing `supervised` scope ends, each such fork is cancelled.
