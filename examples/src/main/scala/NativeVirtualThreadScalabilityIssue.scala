import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger

/** Reproduces a Scala Native 0.5.12 virtual thread scalability issue.
  *
  * Pattern: N virtual threads each block on CompletableFuture.get() while a single "actor" virtual thread processes
  * requests sequentially. At N=100 this works. At N=1000 it livelocks on Native (works on JVM).
  *
  * This is the same pattern used by ox.channels.Actor.ask under high concurrency.
  *
  * To reproduce:
  * {{{
  * sbt examples3/run       # JVM — prints "OK" in ~1s
  * sbt examplesNative3/run # Native — hangs at "Running with N=1000"
  * }}}
  */
object NativeVirtualThreadScalabilityIssue:
  def main(args: Array[String]): Unit =
    for n <- List(10, 100, 500, 1000) do
      println(s"Running with N=$n...")
      run(n)
      println(s"  N=$n OK")
    println("All OK")

  private def run(n: Int): Unit =
    val done = new CompletableFuture[Unit]()

    Thread.ofVirtual().start { () =>
      val queue = new java.util.concurrent.LinkedBlockingQueue[() => Unit]()
      val counter = new AtomicInteger(0)

      // single "actor" thread processing requests sequentially
      val actor = Thread.ofVirtual().start { () =>
        while true do
          val msg = queue.take()
          msg()
      }

      // N concurrent virtual threads, each sending a request and blocking on the response
      val forks = (1 to n).map { _ =>
        val f = new CompletableFuture[Unit]()
        Thread.ofVirtual().start { () =>
          val result = new CompletableFuture[Int]()
          queue.put { () => result.complete(counter.incrementAndGet()): Unit }
          result.get()
          f.complete(()): Unit
        }
        f
      }

      forks.foreach(_.get())
      actor.interrupt()
      done.complete(()): Unit
    }

    done.get()
