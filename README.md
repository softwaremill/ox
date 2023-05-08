# Ox

Developer-friendly structured concurrency library for the JVM, based on:
* [Project Loom](https://openjdk.org/projects/loom/) (virtual threads)
* structured concurrency Java APIs ([JEP 428](https://openjdk.org/jeps/428)) 
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

The loosing computation is interrupted using `Thread.interrupt`. `raceSuccess` waits until both branches finish; this
also applies to the loosing one, which might take a while to clean up after interruption.

### Error handling

* `raceSuccess` returns the first result, or re-throws the last exception
* `raceResult` returns the first result, or re-throws the first exception

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

Scopes can be arbitrarily nested.

### Error handling

Any unhandled exceptions that are thrown in a `fork` block are propagated to the scope's main thread, by interrupting
it and re-throwing the exception there. Hence, any failed fork will cause the entire scope's computation to be 
interrupted, and unless interruptions are intercepted, all other forks will get interrupted as well.

On the other hand, `forkHold` doesn't propagate any exceptions but retains them. The result of the fork must be
explicitly inspected to discover, if the computation failed or succeeded, e.g. using the `Fork.join` method.

## Scoped values

Scoped value replace usages of `ThreadLocal` when using virtual threads and structural concurrency. They are useful to 
propagate auxiliary context, e.g. trace or correlation ids.

Values are bound structurally as well, e.g.:

```scala
import ox.{ForkLocal, fork, scoped}

val v = ForkLocal("a")
scoped {
  println(v.get()) // "a"
  fork {
    v.scopedWhere("x") {
      println(v.get()) // "x"
      fork {
        println(v.get()) // "x"
      }.join()
    }
  }.join()
  println(v.get()) // "a"
}
```

Scoped values propagate across nested scopes.

## Interruptions

When catching exceptions, care must be taken not to catch & fail to propagate an `InterruptedException`. Doing so will
prevent the scope cleanup mechanisms to make appropriate progress, as the scope won't finish until all started threads
complete.

A good solution is to catch only non-fatal exception using `NonFatal`, e.g.:

```scala
import ox.{forever, fork, scoped}

def processSingleItem(): Unit = ()

scoped {
  fork {
    forever {
      try processSingleItem()
      catch case NonFatal(e) => logger.error("Processing error", e)
    }
  }

  // do something else that keeps the scope busy
}
```

## Resources

### In-scope

Resources can be allocated within a scope. They will be released in reverse acquisition order, after the scope completes
(that is, after all forks started within finish). E.g.:

```scala
import ox.useScoped

case class MyResource(c: Int)

def acquire: MyResource = 
  println("acquiring ...")
  MyResource(5)
def release(resource: MyResource): Unit =
  println(s"releasing ${resource.c}...")

scoped {
  val resource1 = useInScope(acquire(10))(release)
  val resource2 = useInScope(acquire(20))(release)
  println(s"Using $resource1 ...")
  println(s"Using $resource2 ...")
}
```

### Scoped

Resources can also be used in a dedicated scope:

```scala
import ox.useScoped

case class MyResource(c: Int)

def acquire: MyResource = 
  println("acquiring ...")
  MyResource(5)
def release(resource: MyResource): Unit =
  println(s"releasing ${resource.c}...")

useScoped(acquire(10))(release) { resource =>
  println(s"Using $resource ...")
}
```

If the resource extends `AutoCloseable`, the `release` method doesn't need to be provided.

## Helper control flow methods

There are some helper methods which might be useful when writing forked code:

* `forever { ... }` repeatedly evaluates the given code block forever
* `repeatWhile { ... }` repeatedly evaluates the given code block, as long as it returns `true`
* `retry(times, sleep) { ... }` retries the given block up to the given number of times
* `uninterruptible { ... }` evaluates the given code block making sure it can't be interrupted

## Syntax

Extension-method syntax can be imported using `import ox.syntax.*`. This allows calling methods such as 
`.fork`, `.raceSuccessWith`, `.parWith`, `.forever`, `.useInScope` directly on code blocks / values.

## & much more

... docs fill follow ... :)

# Development

To compile and test, run:

```
sbt compile
sbt test
```
