# Sources

A source can be used to receive elements from a channel. The `.receive()` method can block, and the result might be
one of the following:

```scala mdoc:compile-only
trait Source[+T]:
  def receive(): T | ChannelClosed

sealed trait ChannelClosed
object ChannelClosed:
  case class Error(reason: Option[Exception]) extends ChannelClosed
  case object Done extends ChannelClosed
```

That is, the result might be a value, or information that the channel is closed. A channel can be done or an error
might have occurred. Using an extension method provided by the `ox.channels.*` import, closed information can be thrown
as an exception using `receive().orThrow: T`.
