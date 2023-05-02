# Ox

Developer-friendly structured concurrency library for the JVM, based on:
* [Project Loom](https://openjdk.org/projects/loom/)
* structured concurrency Java APIs ([JEP 428](https://openjdk.org/jeps/428)), 
* scoped values ([JEP 429](https://openjdk.org/jeps/429))
* [Go](https://golang.org)-like channels
* the [Scala](https://www.scala-lang.org) programming language

Requires JDK 20. Applications need the following JVM flags: `--enable-preview --add-modules jdk.incubator.concurrent`.

[sbt](https://www.scala-sbt.org) dependency:

```scala
"com.softwaremill.ox" %% "core" % "0.0.5"
```

Introductory articles:

* [Prototype Loom-based concurrency API for Scala](https://softwaremill.com/prototype-loom-based-concurrency-api-for-scala/) 
* [Go-like channels using project Loom and Scala](https://softwaremill.com/go-like-channels-using-project-loom-and-scala/)

If you'd have feedback, development ideas or critique, please head to our [community forum](https://softwaremill.community/c/ox/12)!

# API overview

## Run two computations in parallel

```scala
import ox.par

def computation1: Int =
  Thread.sleep(2000)
  1

def computation2: String =
  Thread.sleep(1000)
  "2"

val result: (Int, String) = par(computation1)(computation2)
// (1, "2")
```

If one of the computations fails, the other is interrupted, and `par` waits until both branches complete.

## Race two computations

```scala
import ox.raceSuccess

def computation1: Int =
  Thread.sleep(2000)
  1

def computation2: String =
  Thread.sleep(1000)
  2

val result: Int = raceSuccess(computation1)(computation2)
// 2
```

The other computation is interrupted using `Thread.interrupt`; `raceSuccess` waits until both branches complete.

## Timeout a computation

```scala
import ox.timeout
import scala.concurrent.duration.DurationInt

def computation: Int =
  Thread.sleep(2000)
  1

val result1: Try[Int] = Try(timeout(1.second)(computation)) // failure: TimeoutException
val result2: Try[Int] = Try(timeout(3.seconds)(computation)) // success: 1
```

## Fork & join threads

It's safest to use higher-level methods, such as `par` or `raceSuccess`, however this isn't always sufficient. For
these cases, threads can be started using the structured concurrency APIs described below.

The lifetime of the threads is defined by the structure of the code, and corresponds to the `scoped` block. Once
`scoped` exits, any threads that are still running are interrupted. Hence, it is guaranteed that all threads started 
within `scoped` will finish successfully, with an exception, or due to an interrupt.

```scala
import ox.{fork, scoped}

// same as `par`
scoped {
  val f1 = fork {
    Thread.sleep(2000)
    1
  }

  val f2 = fork {
    Thread.sleep(1000)
    2
  }

  (f1.join(), f2.join())
}
```

It is a compile-time error to use `fork` outside of a `scoped` block. Helper methods might require to be run within
a `scoped` block by requiring the `Ox` capability:

```scala
import ox.{fork, Fork, Ox, scoped}

def forkComputation(p: Int)(using Ox): Fork[Int] = fork {
  Thread.sleep(p * 1000)
  p + 1
}

scoped {
  val f1 = forkComputation(2)
  val f2 = forkComputation(4)
  (f1.join(), f2.join())
}
```

## & much more

... docs fill follow ... :)

# Development

To compile and test, run:

```
sbt compile
sbt test
```
