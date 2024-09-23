# Ox

[![Ideas, suggestions, problems, questions](https://img.shields.io/badge/Discourse-ask%20question-blue)](https://softwaremill.community/c/ox)
[![CI](https://github.com/softwaremill/ox/workflows/CI/badge.svg)](https://github.com/softwaremill/ox/actions?query=workflow%3A%22CI%22)
[![Maven Central](https://maven-badges.herokuapp.com/maven-central/com.softwaremill.ox/core_3/badge.svg)](https://maven-badges.herokuapp.com/maven-central/com.softwaremill.ox/core_3)

Safe direct style concurrency and resiliency for Scala on the JVM. Requires JDK 21 & Scala 3. The areas that we'd like 
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
"com.softwaremill.ox" %% "core" % "0.4.0"
```

Or [scala-cli](https://scala-cli.virtuslab.org):

```scala
//> using dep "com.softwaremill.ox::core:0.4.0"
```

Documentation is available at [https://ox.softwaremill.com](https://ox.softwaremill.com), ScalaDocs can be browsed at [https://javadoc.io](https://www.javadoc.io/doc/com.softwaremill.ox).

## Example

```scala
import ox.*
import ox.either.ok
import ox.channels.*
import ox.resilience.*
import scala.concurrent.duration.*

// run two computations in parallel
def computation1: Int = { sleep(2.seconds); 1 }
def computation2: String = { sleep(1.second); "2" }
val result1: (Int, String) = par(computation1, computation2)
// (1, "2")

// timeout a computation
def computation: Int = { sleep(2.seconds); 1 }
val result2: Either[Throwable, Int] = catching(timeout(1.second)(computation))

// structured concurrency & supervision
supervised {
  forkUser {
    sleep(1.second)
    println("Hello!")
  }
  forkUser {
    sleep(500.millis)
    throw new RuntimeException("boom!")
  }
}
// on exception, ends the scope & re-throws

// retry a computation
def computationR: Int = ???
retry(RetryPolicy.backoff(3, 100.millis, 5.minutes, Jitter.Equal))(computationR)

// create channels & transform them using high-level operations
supervised {
  Source.iterate(0)(_ + 1) // natural numbers
          .transform(_.filter(_ % 2 == 0).map(_ + 1).take(10))
          .foreach(n => println(n.toString))
}

// select from a number of channels
val c = Channel.rendezvous[Int]
val d = Channel.rendezvous[Int]
select(c.sendClause(10), d.receiveClause)

// unwrap eithers and combine errors in a union type
val v1: Either[Int, String] = ???
val v2: Either[Long, String] = ???

val result: Either[Int | Long, String] = either:
  v1.ok() ++ v2.ok()
```

More examples [in the docs!](https://ox.softwaremill.com).

## Other projects

The wider goal of direct style Scala is enabling teams to deliver working software quickly and with confidence. Our
other projects, including [sttp client](https://sttp.softwaremill.com) and [tapir](https://tapir.softwaremill.com),
also include integrations directly tailored towards direct style.

Moreover, also check out the [gears](https://github.com/lampepfl/gears) project, an experimental multi-platform library
also covering direct style Scala.

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

## Project sponsor

We offer commercial development services. [Contact us](https://softwaremill.com) to learn more about us!

## Copyright

Copyright (C) 2023-2024 SoftwareMill [https://softwaremill.com](https://softwaremill.com).
