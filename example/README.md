# Virtual Threads Benchmark: JVM vs Scala Native

Spawns 10,000 virtual threads in an Ox supervised scope, joins all, and
measures wall-clock time. Use this to compare JVM and Scala Native virtual
thread performance.

## Prerequisites

- JDK 21+ (for JVM)
- clang / LLVM 16+ (for Scala Native)

## Run directly via sbt

```bash
# JVM
sbt exampleJVM/run

# Native (first run includes compilation + linking, ~60s)
sbt exampleNative/run
```

## Package standalone binaries

### JVM (uber-jar via sbt-assembly, or just stage)

```bash
# Create a runnable script + lib directory
sbt exampleJVM/stage

# Run
./example/jvm/target/universal/stage/bin/example
```

Or run directly with `java`:

```bash
sbt exampleJVM/package
java -jar example/jvm/target/scala-3.3.7/example_3-*.jar
```

### Native (produces a single static binary)

```bash
# Build the native binary
sbt exampleNative/nativeLink

# Run
./example/native/target/scala-3.3.7/example

# Compare both (run 3 times each for stability):
echo "=== JVM ===" && for i in 1 2 3; do java -jar example/jvm/target/scala-3.3.7/example_3-*.jar; done
echo "=== Native ===" && for i in 1 2 3; do ./example/native/target/scala-3.3.7/example; done
```

## What it does

```scala
supervised {
  for _ <- 1 to 10_000 do
    fork {
      counter.incrementAndGet()
    }
}
// all 10,000 forks joined here
```

This exercises the core virtual thread lifecycle: creation, scheduling,
execution, and join — with no I/O or blocking, so it isolates the raw
thread management overhead.
