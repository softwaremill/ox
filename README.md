# Ox

[![Ideas, suggestions, problems, questions](https://img.shields.io/badge/Discourse-ask%20question-blue)](https://softwaremill.community/c/ox)
[![CI](https://github.com/softwaremill/ox/workflows/CI/badge.svg)](https://github.com/softwaremill/ox/actions?query=workflow%3A%22CI%22)
[![Maven Central](https://maven-badges.herokuapp.com/maven-central/com.softwaremill.ox/core_3/badge.svg)](https://maven-badges.herokuapp.com/maven-central/com.softwaremill.ox/core_3)

Safe direct-style concurrency and resiliency for Scala on the JVM. Requires JDK 21 & Scala 3. The areas that we'd like 
to cover with Ox are:

* concurrency: developer-friendly structured concurrency, high-level concurrency operators, safe low-level primitives, 
  communication between concurrently running computations
* error management: retries, timeouts, a safe approach to error propagation, safe resource management
* scheduling & timers
* resiliency: circuit breakers, bulkheads, rate limiters, backpressure

All of the above should allow for observability of the orchestrated business logic. We aim to enable writing simple, 
expression-oriented code in functional style. We’d like to keep the syntax overhead to a minimum, preserving 
developer-friendly stack traces, and without compromising performance.

Some of the above are already addressed in the API, some are coming up in the future. We’d love your help in shaping 
the project!

To test Ox, use the following dependency, using either [sbt](https://www.scala-sbt.org):

```scala
"com.softwaremill.ox" %% "core" % "0.5.3"
```

Or [scala-cli](https://scala-cli.virtuslab.org):

```scala
//> using dep "com.softwaremill.ox::core:0.5.3"
```

Documentation is available at [https://ox.softwaremill.com](https://ox.softwaremill.com), ScalaDocs can be browsed at [https://javadoc.io](https://www.javadoc.io/doc/com.softwaremill.ox).

## Tour of ox

Run two computations [in parallel](https://ox.softwaremill.com/latest/high-level-concurrency/par.html):

```scala mdoc:compile-only
def computation1: Int = { sleep(2.seconds); 1 }
def computation2: String = { sleep(1.second); "2" }
val result1: (Int, String) = par(computation1, computation2)
// (1, "2")
```

[Timeout](https://ox.softwaremill.com/latest/high-level-concurrency/timeout.html) a computation:

```scala mdoc:compile-only
def computation3: Int = { sleep(2.seconds); 1 }
val result2: Either[Throwable, Int] = either.catching(timeout(1.second)(computation3))
// `timeout` only completes once the loosing branch is interrupted & done
```

[Race](https://ox.softwaremill.com/latest/high-level-concurrency/race.html) two computations:

```scala mdoc:compile-only
def computation4: Int = { sleep(2.seconds); 1 }
def computation5: Int = { sleep(1.second); 2 }
val result3: Int = raceSuccess(computation4, computation5)
// as before, the loosing branch is interrupted & awaited before returning a result
```

[Structured concurrency](https://ox.softwaremill.com/latest/structured-concurrency/fork-join.html) & supervision:

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

[Retry](https://ox.softwaremill.com/latest/utils/retries.html) a computation:

```scala mdoc:compile-only
def computationR: Int = ???
retry(RetryConfig.backoff(3, 100.millis, 5.minutes, Jitter.Equal))(computationR)
```

[Repeat](https://ox.softwaremill.com/latest/utils/repeat.html) a computation:

```scala mdoc:compile-only
def computationR: Int = ???
repeat(RepeatConfig.fixedRateForever(100.millis))(computationR)
```

Allocate a [resource](https://ox.softwaremill.com/latest/utils/resources.html) in a scope:

```scala mdoc:compile-only
supervised {
  val writer = useCloseableInScope(new java.io.PrintWriter("test.txt"))
  // ... use writer ...
} // writer is closed when the scope ends (successfully or with an error)
```

[Create an app](https://ox.softwaremill.com/latest/utils/oxapp.html) which shuts down cleanly when interrupted with SIGINT/SIGTERM:

```scala mdoc:compile-only
object MyApp extends OxApp:
  def run(args: Vector[String])(using Ox): ExitCode =
    // ... your app's code ...
    // might use fork {} to create top-level background threads
    ExitCode.Success
```

Simple type-safe [actors](https://ox.softwaremill.com/latest/utils/actors.html):

```scala mdoc:compile-only
class Stateful { def increment(delta: Int): Int = ??? }

supervised:
  val ref = Actor.create(new Stateful)
  // ref can be shared across forks, but only within the concurrency scope
  ref.ask(_.increment(5))    
```

Create a simple [flow](https://ox.softwaremill.com/latest/streaming/flows.html) & transform using a functional API:

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

Use completable high-performance [channels](https://ox.softwaremill.com/latest/streaming/channels.html) for inter-fork communication within concurrency scopes:

```scala mdoc:compile-only
val c = Channel.buffered[String](8)
c.send("Hello,")
c.send("World")
c.done()
```

[Select](https://ox.softwaremill.com/latest/streaming/selecting-from-channels.html) from Go-like channels:

```scala mdoc:compile-only
val c = Channel.rendezvous[Int]
val d = Channel.rendezvous[Int]
select(c.sendClause(10), d.receiveClause)
```

[Unwrap eithers](https://ox.softwaremill.com/latest/basics/error-handling.html) and combine errors in a union type:

```scala mdoc:compile-only
val v1: Either[Int, String] = ???
val v2: Either[Long, String] = ???

val result: Either[Int | Long, String] = either:
  v1.ok() ++ v2.ok()
```

[Pipe & tap](https://ox.softwaremill.com/latest/utils/control-flow.html) values to functions to use the dot-syntax:

```scala mdoc:compile-only
def compute: Int = ???
def computeMore(v: Int): Long = ???
compute
  .pipe(2 * _)
  .tap(println)
  .pipe(computeMore)  
```

More [in the docs!](https://ox.softwaremill.com).

## Other projects

The wider goal of direct-style Scala is enabling teams to deliver working software quickly and with confidence. Our
other projects, including [sttp client](https://sttp.softwaremill.com) and [tapir](https://tapir.softwaremill.com),
also include integrations directly tailored towards direct style.

Moreover, also check out the [gears](https://github.com/lampepfl/gears) project, an experimental multi-platform library
also covering direct-style Scala.

## Contributing

All suggestions welcome :)

To compile and test, run:

```
sbt compile
sbt test
```

See the list of [issues](https://github.com/softwaremill/ox/issues) and pick one! Or report your own.

If you are having doubts on the _why_ or _how_ something works, don't hesitate to ask a question on
[discourse](https://softwaremill.community/c/ox) or via github. This probably means that the documentation, ScalaDocs or
code is unclear and can be improved for the benefit of all.

In order to develop the documentation, you can use the `doc/watch.sh` script, which runs Sphinx using Python.
Use `doc/requirements.txt` to set up your Python environment with `pip`. 
Alternatively, if you're a Nix user, run `nix develop` in `doc/` to start a shell with an environment allowing to run `watch.sh`.
Moreover, you can use the `compileDocumentation` sbt task to verify, that all code snippets compile properly.

When you have a PR ready, take a look at our ["How to prepare a good PR" guide](https://softwaremill.community/t/how-to-prepare-a-good-pr-to-a-library/448). Thanks! :)

## Project sponsor

We offer commercial development services. [Contact us](https://softwaremill.com) to learn more about us!

## Copyright

Copyright (C) 2023-2024 SoftwareMill [https://softwaremill.com](https://softwaremill.com).
