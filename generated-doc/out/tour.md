# A tour of ox


Run two computations [in parallel](high-level-concurrency/par.md):

```scala
def computation1: Int = { sleep(2.seconds); 1 }
def computation2: String = { sleep(1.second); "2" }
val result1: (Int, String) = par(computation1, computation2)
// (1, "2")
```

[Timeout](high-level-concurrency/timeout.md) a computation:

```scala
def computation3: Int = { sleep(2.seconds); 1 }
val result2: Either[Throwable, Int] = either.catching(timeout(1.second)(computation3))
// `timeout` only completes once the loosing branch is interrupted & done
```

[Race](high-level-concurrency/race.md) two computations:

```scala
def computation4: Int = { sleep(2.seconds); 1 }
def computation5: Int = { sleep(1.second); 2 }
val result3: Int = raceSuccess(computation4, computation5)
// as before, the loosing branch is interrupted & awaited before returning a result
```

[Structured concurrency](structured-concurrency/fork-join.md) & supervision:

```scala
// equivalent of par
supervised {
  val f1 = fork { sleep(2.seconds); 1 }
  val f2 = fork { sleep(1.second); 2 }
  (f1.join(), f2.join())
}
```

Error handling within a structured concurrency scope:

```scala
supervised {
  forkUser:
    sleep(1.second)
    println("Hello!")

  forkUser:
    sleep(500.millis)
    throw new RuntimeException("boom!")
}
```

[Retry](scheduling/retries.md) a computation:

```scala
def computationR: Int = ???
retry(Schedule.exponentialBackoff(100.millis).maxRepeats(5)
  .jitter().maxInterval(5.minutes))(computationR)
```

[Repeat](scheduling/repeat.md) a computation:

```scala
def computationR: Int = ???
repeat(Schedule.fixedInterval(100.millis))(computationR)
```

[Rate limit](scheduling/rate-limiter.md) computations:

```scala
supervised:
  val rateLimiter = RateLimiter.fixedWindowWithStartTime(2, 1.second)
  rateLimiter.runBlocking({ /* ... */ })
```

Allocate a [resource](utils/resources.md) in a scope:

```scala
supervised {
  val writer = useCloseableInScope(new java.io.PrintWriter("test.txt"))
  // ... use writer ...
} // writer is closed when the scope ends (successfully or with an error)
```

[Create an app](utils/oxapp.md) which shuts down cleanly when interrupted with SIGINT/SIGTERM:

```scala
object MyApp extends OxApp:
  def run(args: Vector[String])(using Ox): ExitCode =
    // ... your app's code ...
    // might use fork {} to create top-level background threads
    ExitCode.Success
```

Simple type-safe [actors](utils/actors.md):

```scala
class Stateful { def increment(delta: Int): Int = ??? }

supervised:
  val ref = Actor.create(new Stateful)
  // ref can be shared across forks, but only within the concurrency scope
  ref.ask(_.increment(5))
```

Create a simple [flow](streaming/flows.md) & transform using a functional API:

```scala
Flow.iterate(0)(_ + 1) // natural numbers
  .filter(_ % 2 == 0)
  .map(_ + 1)
  .intersperse(5)
  // compute the running total
  .mapStateful(0) { (state, value) =>
    val newState = state + value
    (newState, newState)
  }
  .take(10)
  .runForeach(n => println(n.toString))
```

Create flows which perform I/O and manage concurrency:

```scala
def sendHttpRequest(entry: String): Unit = ???
Flow
  .fromInputStream(this.getClass().getResourceAsStream("/list.txt"))
  .linesUtf8
  .mapPar(4)(sendHttpRequest)
  .runDrain()
```

Merge two flows, properly handling the failure of either branches:

```scala
val f1 = Flow.tick(123.millis, "left")
val f2 = Flow.tick(312.millis, "right")
f1.merge(f2).take(100).runForeach(println)
```

Integrate flow with other components using an imperative API:

```scala
def readNextBatch(): List[String] = ???
Flow.usingEmit { emit =>
  forever:
    readNextBatch().foreach(emit.apply)
}
```

Use completable high-performance [channels](streaming/channels.md) for inter-fork communication within concurrency scopes:

```scala
val c = Channel.buffered[String](8)
c.send("Hello,")
c.send("World")
c.done()
```

[Select](streaming/selecting-from-channels.md) from Go-like channels:

```scala
val c = Channel.rendezvous[Int]
val d = Channel.rendezvous[Int]
select(c.sendClause(10), d.receiveClause)
```

[Unwrap eithers](basics/error-handling.md) and combine errors in a union type:

```scala
val v1: Either[Int, String] = ???
val v2: Either[Long, String] = ???

val result: Either[Int | Long, String] = either:
  v1.ok() ++ v2.ok()
```

[Pipe & tap](utils/control-flow.md) values to functions to use the dot-syntax:

```scala
def compute: Int = ???
def computeMore(v: Int): Long = ???
compute
  .pipe(2 * _)
  .tap(println)
  .pipe(computeMore)
```

Dive into the specific documentation sections for more details, variants and functionalities!
