# Channels

A channel is like a queue (values can be sent/received), but additionally channels support:

* completion (a source can be `done`)
* downstream error propagation
* `select`ing exactly one channel clause to complete, where clauses include send and receive operations

Creating a channel is a light-weight operation:

```scala mdoc:compile-only
import ox.channels.*
val c = Channel.bufferedDefault[String]
```

This uses the default buffer size (16). It's also possible to create channels with other buffer sizes, as well as 
rendezvous or unlimited channels:

```scala mdoc:compile-only
import ox.channels.*
val c1 = Channel.rendezvous[String]
val c2 = Channel.buffered[String](5)
val c3 = Channel.unlimited[String]
```

In rendezvous channels, a sender and receiver must "meet" to exchange a value. Hence, `.send` always blocks, unless 
there's another thread waiting on a `.receive`. In buffered channels, `.send` only blocks when the buffer is full. 
In an unlimited channel, sending never blocks.

Channels implement two traits: `Source` and `Sink`.
