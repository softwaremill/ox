# Streaming APIs

Ox provides two complementary APIs for defining streaming data transformation pipelines.

The first API uses an **imperative style** and is implemented using [channels](channels.md). As part of the code which defines how the data should be transformed, you can use the (blocking) `receive()` and `send()` methods on channels. You'll also often directly use [`Ox` concurrency scopes](../structured-concurrency/index.md) and [`fork`s](../structured-concurrency/fork-join.md). For example:

```scala mdoc:compile-only
import ox.*
import ox.channels.*

def parseNumbers(incoming: Source[String])(using Ox, BufferCapacity): Source[Int] =
  val results = BufferCapacity.newChannel[Int]
  forkPropagate(results) {
    repeatWhile:
      incoming.receiveOrClosed() match
        case ChannelClosed.Done     => results.doneOrClosed(); false
        case ChannelClosed.Error(r) => results.errorOrClosed(r); false
        case t: String => 
          t.split(" ").flatMap(_.toIntOption).foreach: n =>
            println(s"Got: $n")
            results.send(n);
          true
  }  
  results
```

The second API uses a **functional style**, implemented as [flows](flows.md). A flow lets you stack multiple data transformations using high-level methods such as `map`, `mapPar`, `grouped`, `async`, `merge` and more. For example:

```scala mdoc:compile-only
import ox.channels.BufferCapacity
import ox.flow.*

def invokeService(n: Int): String = ???

def sendParsedNumbers(incoming: Flow[String])(using BufferCapacity): Unit =
  incoming
    .mapConcat(_.split(" ").flatMap(_.toIntOption))
    .tap(n => println(s"Got: $n"))
    .mapPar(8)(invokeService)
    .runForeach(r => println("Result: $r"))
```

A flow **describes** the operations to perform; only when one of its `run` method is invoked, actual data processing starts. That is, a flow is lazily-evaluated. As part of implementing the individual transformation stages of a flow, channels, concurrency scopes and forks as used. Flows are a higher-level API, built on top of channels and forks.

While channels implement a "hot streams" approach to defining data transformation pipelines, flows correspond to "cold streams".

You can use both approaches in a single pipeline, depending which approach better fits the task at hand. It's straightforward to convert a channel to a flow, and to run a flow to a channel.

