# Race two computations

A number of computations can be raced against each other using the `race` method, for example:

```scala mdoc:compile-only
import ox.{race, sleep}
import scala.concurrent.duration.*

def computation1: Int =
  sleep(2.seconds)
  1

def computation2: Int =
  sleep(1.second)
  2

val result: Int = race(computation1, computation2)
// 2
```

The losing computation is interrupted. `race` waits until both branches finish; this also applies to the losing one, 
which might take a while to clean up after interruption.

It is also possible to race a sequence of computations, given as `Seq[() => T]`.

## Race variants

* `race` returns the first result, or re-throws the last exception
* `raceResult` returns the first result, or re-throws the first exception (the first computation which finishes in any 
  way is the "winner")

## Using application errors

Some values might be considered as application errors. In a computation returns such an error, `race` continues waiting
if there are other computations in progress, same as when an exception is thrown. Ultimately, `race` either throws
the first exception, or the first application error that has been reported (whichever comes first).

It's possible to use an arbitrary [error mode](../basics/error-handling.md) by providing it as the initial argument to `race`.
Alternatively, a built-in version using `Either` is available as `raceEither`:

```scala mdoc:compile-only
import ox.{raceEither, sleep}
import scala.concurrent.duration.*

raceEither({
  sleep(200.millis)
  Left(-1)
}, {
  sleep(500.millis)
  Right("ok")
}, {
  sleep(1.second)
  Right("also ok")
})
```

Here, the example returns `Right("ok")`; the first result is considered an error (a `Left`), and the third computation
is cancelled.

