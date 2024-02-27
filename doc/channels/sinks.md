# Sinks

Data can be sent to a channel using `Sink.send`. Once no more data items are available, completion can be signalled 
downstream using `Sink.done`. If there's an error when producing data, this can be signalled using `Sink.error`:

```scala mdoc:compile-only
import ox.{fork, supervised}
import ox.channels.*

val c = Channel.rendezvous[String]
supervised {
  fork {
    c.send("Hello")
    c.send("World")
    c.done()
  }

  // TODO: receive
}
```

`.send` blocks the thread, hence usually channels are shared across forks to communicate data between them. As Ox is
designed to work with Java 21+ and Virtual Threads, blocking is a cheap operation that might be done frequently.
