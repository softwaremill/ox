# Sinks

Data can be sent to a channel using `.send`. Once no more data items are available, completion can be signalled using
`.done`. If there's an error when producing data, this can be signalled using `.error`:

```scala
import ox. {fork, supervised}
import ox.channels.*

val c = Channel[String]()
supervised {
  fork {
    c.send("Hello")
    c.send("World")
    c.done()
  }

  // TODO: receive
}
```

`.send` is blocking, hence usually channels are shared across forks to communicate data between them.
