# Quick example

Below is a quick example of some of Ox's APIs in action. Dive into the specific documentation sections for more details, 
variants and functionalities!

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
