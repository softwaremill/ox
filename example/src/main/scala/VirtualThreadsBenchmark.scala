import ox.*

import java.util.concurrent.atomic.AtomicLong

/** Spawns 10,000 virtual threads in a supervised scope, each incrementing a shared counter, then
  * joins all. Measures wall-clock time to compare JVM vs Scala Native virtual thread performance.
  */
object VirtualThreadsBenchmark:
  def main(args: Array[String]): Unit =
    val n = 10_000
    val counter = new AtomicLong(0L)

    val start = System.nanoTime()

    supervised {
      for _ <- 1 to n do
        fork {
          // simulate minimal work per thread
          counter.incrementAndGet()
        }
    }

    val elapsed = (System.nanoTime() - start) / 1_000_000

    assert(counter.get() == n, s"Expected $n, got ${counter.get()}")
    println(s"Spawned and joined $n virtual threads in ${elapsed}ms")
