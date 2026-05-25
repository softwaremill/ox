import ox.*

import java.util.concurrent.atomic.AtomicLong

/** Spawns 100,000 virtual threads in a supervised scope, each incrementing a shared counter, then joins all. Measures wall-clock time to
  * compare JVM vs Scala Native performance.
  *
  * Prerequisites: JDK 21+ (JVM), clang/LLVM 16+ (Native).
  *
  * To package & run:
  * {{{
  * # JVM fat jar
  * sbt examplesJVM/assembly
  * java -jar examples/.jvm/target/scala-3.3.7/examples-assembly.jar
  *
  * # Native binary
  * sbt examplesNative/nativeLink
  * ./examples/.native/target/scala-3.3.7/examples
  *
  * # Compare (3 iterations each):
  * for i in 1 2 3; do java -jar examples/.jvm/target/scala-3.3.7/examples-assembly.jar; done
  * for i in 1 2 3; do ./examples/.native/target/scala-3.3.7/examples; done
  * }}}
  */
object VirtualThreadsNativeJvmBenchmark:
  def main(args: Array[String]): Unit =
    val n = 100_000
    val counter = new AtomicLong(0L)

    val start = System.nanoTime()

    supervised {
      for _ <- 1 to n do
        fork {
          counter.incrementAndGet()
        }
    }

    val elapsed = (System.nanoTime() - start) / 1_000_000

    assert(counter.get() == n, s"Expected $n, got ${counter.get()}")
    println(s"Spawned and joined $n virtual threads in ${elapsed}ms")
  end main
end VirtualThreadsNativeJvmBenchmark
