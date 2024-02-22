# Channels

A channel is like a queue (data can be sent/received), but additionally channels support:

* completion (a source can be `done`)
* error propagation downstream
* receiving exactly one value from a number of channels

Creating a channel is a light-weight operation:

```scala
import ox.channels.*
val c = Channel[String]()
```

By default, channels are unbuffered, that is a sender and receiver must "meet" to exchange a value. Hence, `.send`
always blocks, unless there's another thread waiting on a `.receive`.

Buffered channels can be created by providing a non-zero capacity:

```scala
import ox.channels.*
val c = Channel[String](5)
```

Unbounded channels can be created using `Channel.unlimited[T]`.

Channels implement two traits: `Source` and `Sink`.
