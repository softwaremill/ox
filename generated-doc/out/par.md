# Running computations in parallel

A number of computations can be ran in parallel using the `par` method, for example:

```scala
import ox.{par, sleep}
import scala.concurrent.duration.*

def computation1: Int =
  sleep(2.seconds)
  1

def computation2: String =
  sleep(1.second)
  "2"

val result: (Int, String) = par(computation1, computation2)
// (1, "2")
```

If any of the computations fails, the other is interrupted. In such case, `par` waits until both branches complete 
and then re-throws the exception.

It's also possible to run a sequence of computations given as a `Seq[() => T]` in parallel, optionally limiting the
parallelism using `parLimit`:

```scala
import ox.{parLimit, sleep}
import scala.concurrent.duration.*

def computation(n: Int): Int =
  sleep(1.second)
  println(s"Running $n")
  n*2

val computations = (1 to 20).map(n => () => computation(n))
val result: Seq[Int] = parLimit(5)(computations)
// (1, "2")
```

## Using application errors

Some values might be considered as application errors. In a computation returns such an error, other computations are 
interrupted, same as when an exception is thrown. The error is then returned by the `par` method.

It's possible to use an arbitrary [error mode](error-handling.md) by providing it as the initial argument to `par`.
Alternatively, a built-in version using `Either` is available as `parEither`:

```scala
import ox.{parEither, sleep}
import scala.concurrent.duration.*

val result = parEither(
  {
    sleep(200.millis)
    Right("ok")
  }, {
    sleep(100.millis)
    Left(-1)
  }
)

// result is Left(-1), the other branch is interrupted
```
