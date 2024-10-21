# A tour of ox

```scala mdoc:invisible
import ox.*
import ox.either.ok
import ox.channels.*
import ox.flow.Flow
import ox.resilience.*
import ox.scheduling.*
import scala.concurrent.duration.*
```

Run two computations [in parallel](high-level-concurrency/par.md):

```scala mdoc:compile-only
def computation1: Int = { sleep(2.seconds); 1 }
def computation2: String = { sleep(1.second); "2" }
val result1: (Int, String) = par(computation1, computation2)
// (1, "2")
```

[Timeout](high-level-concurrency/timeout.md) a computation:

```scala mdoc:compile-only
def computation3: Int = { sleep(2.seconds); 1 }
val result2: Either[Throwable, Int] = either.catching(timeout(1.second)(computation3))
// `timeout` only completes once the loosing branch is interrupted & done
```

[Race](high-level-concurrency/race.md) two computations:

```scala mdoc:compile-only
def computation4: Int = { sleep(2.seconds); 1 }
def computation5: Int = { sleep(1.second); 2 }
val result3: Int = raceSuccess(computation4, computation5)
// as before, the loosing branch is interrupted & awaited before returning a result
```

[Structured concurrency](structured-concurrency/fork-join.md) & supervision:

```scala mdoc:compile-only
// equivalent of par
supervised {
  val f1 = fork { sleep(2.seconds); 1 }
  val f2 = fork { sleep(1.second); 2 }
  (f1.join(), f2.join())
}
```

Error handling within a structured concurrency scope:

```scala mdoc:compile-only
supervised {
  forkUser:
    sleep(1.second)
    println("Hello!")

  forkUser:
    sleep(500.millis)
    throw new RuntimeException("boom!")
}
// on exception, all other forks are interrupted ("let it crash")
// the scope ends & re-throws only when all forks complete (no "leftovers")
```

[Retry](utils/retries.md) a computation:

```scala mdoc:compile-only
def computationR: Int = ???
retry(RetryConfig.backoff(3, 100.millis, 5.minutes, Jitter.Equal))(computationR)
```

[Repeat](utils/repeat.md) a computation:

```scala mdoc:compile-only
def computationR: Int = ???
repeat(RepeatConfig.fixedRateForever(100.millis))(computationR)
```

Allocate a [resource](utils/resources.md) in a scope:

```scala mdoc:compile-only
supervised {
  val writer = useCloseableInScope(new java.io.PrintWriter("test.txt"))
  // ... use writer ...
} // writer is closed when the scope ends (successfully or with an error)
```

[Create an app](utils/oxapp.md) which shuts down cleanly when interrupted with SIGINT/SIGTERM:

```scala mdoc:compile-only
object MyApp extends OxApp:
  def run(args: Vector[String])(using Ox): ExitCode =
    // ... your app's code ...
    // might use fork {} to create top-level background threads
    ExitCode.Success
```

Simple type-safe [actors](utils/actors.md):

```scala mdoc:compile-only
class Stateful { def increment(delta: Int): Int = ??? }

supervised:
  val ref = Actor.create(new Stateful)
  // ref can be shared across forks, but only within the concurrency scope
  ref.ask(_.increment(5))    
```

Create a simple [flow](streaming/flows.md) & transform using a functional API:

```scala mdoc:compile-only
Flow.iterate(0)(_ + 1) // natural numbers
  .filter(_ % 2 == 0)
  .map(_ + 1)
  .intersperse(5)
  // compute the running total
  .mapStateful(() => 0) { (state, value) =>
    val newState = state + value
    (newState, newState)
  }
  .take(10)
  .runForeach(n => println(n.toString))
```

Create flows which perform I/O and manage concurrency:

```scala mdoc:compile-only
def sendHttpRequest(entry: String): Unit = ???
Flow
  .fromInputStream(this.getClass().getResourceAsStream("/list.txt"))
  .linesUtf8
  .mapPar(4)(sendHttpRequest)
  .runDrain()
```

Merge two flows, properly handling the failure of either branches:

```scala mdoc:compile-only
val f1 = Flow.tick(123.millis, "left")
val f2 = Flow.tick(312.millis, "right")
f1.merge(f2).take(100).runForeach(println)
```

Integrate flow with other components using an imperative API:

```scala mdoc:compile-only
def readNextBatch(): List[String] = ???
Flow.usingEmit { emit =>
  forever:
    readNextBatch().foreach(emit.apply)
}
```

Use completable high-performance [channels](streaming/channels.md) for inter-fork communication within concurrency scopes:

```scala mdoc:compile-only
val c = Channel.buffered[String](8)
c.send("Hello,")
c.send("World")
c.done()
```

[Select](streaming/selecting-from-channels.md) from Go-like channels:

```scala mdoc:compile-only
val c = Channel.rendezvous[Int]
val d = Channel.rendezvous[Int]
select(c.sendClause(10), d.receiveClause)
```

[Unwrap eithers](basics/error-handling.md) and combine errors in a union type:

```scala mdoc:compile-only
val v1: Either[Int, String] = ???
val v2: Either[Long, String] = ???

val result: Either[Int | Long, String] = either:
  v1.ok() ++ v2.ok()
```

[Pipe](utils/control-flow.md) a value to a function to use the dot-syntax:

```scala mdoc:compile-only
(5+2)
  .pipe(2 * _)
  .pipe(println)  
```

Dive into the specific documentation sections for more details, variants and functionalities!
