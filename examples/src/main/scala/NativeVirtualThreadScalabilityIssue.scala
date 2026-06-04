import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger

/** Reproduces a Scala Native 0.5.12 virtual thread scalability issue.
  *
  * Pattern: N virtual threads each block on CompletableFuture.get() while a single "actor" virtual thread processes
  * requests sequentially. At N=100 this works. At N>=500 it livelocks on Native (works on JVM).
  *
  * This is the same pattern used by ox.channels.Actor.ask under high concurrency.
  *
  * To reproduce:
  * {{{
  * sbt examples3/run       # JVM — prints "All OK"
  * sbt examplesNative3/run # Native — hangs at N>=500
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
    val queue = new java.util.concurrent.LinkedBlockingQueue[() => Unit]()
    val counter = new AtomicInteger(0)

    // single "actor" thread processing requests sequentially
    val actor = Thread.ofVirtual().start { () =>
      while true do
        val msg = queue.take()
        msg()
    }

    // N concurrent virtual threads, each sending a request and blocking on the response
    val threads = (1 to n).map { _ =>
      Thread.ofVirtual().start { () =>
        val result = new CompletableFuture[Int]()
        queue.put { () => result.complete(counter.incrementAndGet()): Unit }
        result.get(): Unit
      }
    }

    threads.foreach(_.join())
    actor.interrupt()
