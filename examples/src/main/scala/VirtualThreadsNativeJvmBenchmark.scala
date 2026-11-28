import ox.*

import java.util.concurrent.atomic.AtomicLong

/** Spawns 100,000 virtual threads in a supervised scope, each incrementing a shared counter, then joins all. Measures wall-clock time to
  * compare JVM vs Scala Native performance.
  *
  * This is a rough, single-shot measurement. For proper JVM & Native benchmarks, see
  * [[https://github.com/WojciechMazur/scala-microbench-harness scala-microbench-harness]] (JMH on multiple Scala platforms).
  *
  * Prerequisites: JDK 21+ (JVM), clang/LLVM 16+ (Native).
  *
  * To package & run:
  * {{{
  * # JVM fat jar
  * sbt examples/assembly
  * java -jar target/out/jvm/scala-3.9.0/examples/examples-assembly.jar
  *
  * # Native binary
  * sbt examplesNative/nativeLink
  * ./target/out/native0.5/scala-3.9.0/examples/examples
  *
  * # Compare (3 iterations each):
  * for i in 1 2 3; do java -jar target/out/jvm/scala-3.9.0/examples/examples-assembly.jar; done
  * for i in 1 2 3; do ./target/out/native0.5/scala-3.9.0/examples/examples; done
  * }}}
  */
object VirtualThreadsNativeJvmBenchmark:
  def main(args: Array[String]): Unit =
    val n = 100_000
    val counter = new AtomicLong(0L)

    val start = System.nanoTime()

    supervised {
      for _ <- 1 to n do
        forkUser {
          counter.incrementAndGet()
        }
    }

    val elapsed = (System.nanoTime() - start) / 1_000_000

    assert(counter.get() == n, s"Expected $n, got ${counter.get()}")
    println(s"Spawned and joined $n virtual threads in ${elapsed}ms")
  end main
end VirtualThreadsNativeJvmBenchmark
