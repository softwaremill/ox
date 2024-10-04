# Flows

A `Flow[T]` describes an asynchronous data transformation pipeline. When run, it emits elements of type `T`.

Flows are lazy, evaluation (and any effects) happen only when the flow is run. Flows might be finite or infinite; in the latter case running a flow never ends normally; it might be interrupted, though. Finally, any exceptions that occur when evaluating the flow's logic will be thrown when running the flow, after any cleanup logic completes.

## Creating flows

There's a number of methods on the `Flow` companion object that can be used to create a flow:

```scala mdoc:compile-only
import ox.flow.Flow
import scala.concurrent.duration.*

Flow.fromValues(1, 2, 3) // a finite flow
Flow.tick(1.second, "x") // an infinite flow, emitting "x" every second
Flow.iterate(0)(_ + 1) // natural numbers
```

Note that creating a flow as above doesn't emit any elements, or execute any of the flow's logic. Only when run, the elements are emitted and any effects that are part of the flow's stages happen.

Flows can be also created by providing arbitrary element-emitting logic:

```scala mdoc:compile-only
import ox.flow.Flow

def isNoon(): Boolean = ???

Flow.usingEmit: emit =>
  emit(1)
  for i <- 4 to 50 do emit(i)
  if isNoon() then emit(42)
```

Finally, flows can be created using [channel](channels.md) `Source`s:

```scala mdoc:compile-only
import ox.channels.Channel
import ox.flow.Flow
import ox.{fork, supervised}

val ch = Channel.bufferedDefault[Int]
supervised:
  fork:
    ch.send(1)
    ch.send(15)
    ch.send(-2)
    ch.done()

  Flow.fromSource(ch) // TODO: transform the flow further & run
```

## Transforming flows: basics

Multiple transformation stages can be added to a flow, each time returning a new `Flow` instance, describing the extended pipeline. As before, no elements are emitted or transformed until the flow is run, as flows are lazy. There's a number of pre-defined transformation stages, many of them similar in function to corresponding methods on Scala's collections:

```scala mdoc:compile-only
import ox.flow.Flow

Flow.fromValues(1, 2, 3, 5, 6)
  .map(_ * 2)
  .filter(_ % 2 == 0)
  .take(3)
  .zip(Flow.repeat("a number"))
  .interleave(Flow.repeat((0, "also a number")))
  // etc., TODO: run the flow
```

You can also define arbitrary element-emitting logic, using each incoming element using `.mapUsingEmit`, similarly to `Flow.usingEmit` above.

## Running flows

Flows have to be run, for any processing to happen. This can be done with one of the `.run...` methods. For example:

```scala mdoc:compile-only
import ox.flow.Flow
import scala.concurrent.duration.*

Flow.fromValues(1, 2, 3).runToList() // List(1, 2, 3)
Flow.fromValues(1, 2, 3).runForeach(println)
Flow.tick(1.second, "x").runDrain() // never finishes
```

Running a flow is a blocking operation. Unless asynchronous boundaries are present (explicit or implicit, more on this below), the entire processing happens on the calling thread. For example such a pipeline:

```scala mdoc:compile-only
import ox.flow.Flow

Flow.fromValues(1, 2, 3, 5, 6)
  .map(_ * 2)
  .filter(_ % 2 == 0)
  .runToList()
```

Processes the elements one-by-one on the thread that is invoking the run method.

## Transforming flows: concurrency

A number of flow transformations introduces asynchronous boundaries. For example, `.mapPar(parallelism)(mappingFunction)` describes a flow, which runs the pipeline defined so far in the background, emitting elements to a [channel](channels.md). Another [fork](../structured-concurrency/fork-join.md) reads these elements and runs up to `parallelism` invocations of `mappingFunction` concurrently. Mapped elements are then emitted by the returned flow.

Behind the scenes, an `Ox` concurrency scope is created along with a number of forks. In case of any exceptions, everything is cleaned up before the flow propagates the exceptions. The `.mapPar` logic ensures that any exceptions from the preceding pipeline are propagated through the channel.

Some other stages which introduce concurrency include `.merge`, `.interleave`, `.groupedWithin` and [I/O](io.md) stages. The created channels serve as buffers between the pipeline stages, and their capacity is defined by the `BufferCapacity` in scope (a default instance is available, if not provided explicitly).

Explicit asynchronous boundaries can be inserted using `.async()`. This might be useful if producing the next element to emit, and consuming the previous should run concurrently; or if the processing times of the consumer varies, and the producer should buffer up elements.

## Interoperability with channels

Flows can be created from channels, and run to channels. For example:

```scala mdoc:compile-only
import ox.Ox
import ox.channels.{BufferCapacity, Source}
import ox.flow.Flow

def transformChannel(ch: Source[String])(using Ox, BufferCapacity): Source[Int] =
  Flow.fromSource(ch)
    .mapConcat(_.split(" "))
    .mapConcat(_.toIntOption)
    .filter(_ % 2 == 0)
    .runToChannel()
```

The method above needs to be run within a concurrency scope, as `.runToChannel()` creates a background fork which runs the pipeline described by the flow, and emits its elements onto the returned channel.

## Text transformations

When dealing with flows of `Chunk[Byte]` or `String`s, you can leverage following built-in combinators for useful transformations:

* `encodeUtf8` encodes a `Flow[String]` into a `Flow[Chunk[Byte]]`
* `linesUtf8` decodes a `Flow[Chunk[Byte]]` into a `Flow[String]`. Assumes that the input represents text with line breaks. The `String` elements emitted by resulting `Flow[String]` represent text lines.
* `decodeStringUtf8` to decode a `Flow[Chunk[Byte]]` into a `Flow[String]`, without handling line breaks, just processing input bytes as UTF-8 characters, even if a multi-byte character is divided into two chunks.

Such operations may be useful when dealing with I/O like files, `InputStream`, etc. See [I/O](io.md).

## Logging

Ox does not have any integrations with logging libraries, but it provides a simple way to log elements emitted by flows using the `.tap` method:

```scala mdoc:compile-only
import ox.flow.Flow

Flow.fromValues(1, 2, 3)
  .tap(n => println(s"Received: $n"))
  .runToList()
```