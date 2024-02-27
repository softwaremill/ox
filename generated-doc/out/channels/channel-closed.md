# Handling closed channels

By default, `Sink.send` and `Source.receive` methods will throw a `ChannelClosedException`, if the channel is already
closed:

```scala
enum ChannelClosedException(cause: Option[Throwable]) extends Exception(cause.orNull):
  case Error(cause: Throwable) extends ChannelClosedException(Some(cause))
  case Done() extends ChannelClosedException(None)
```

Alternatively, you can call `Sink.sendSafe` or `Source.receiveSafe`, which return union types:

```scala
trait Source[+T]:
  def receive(): T
  def receiveSafe(): T | ChannelClosed

trait Sink[-T]:
  def send(value: T): Unit
  def sendSafe(value: T): Unit | ChannelClosed
  def done(): Unit
  def doneSafe(): Unit | ChannelClosed
  def error(cause: Throwable): Unit
  def errorSafe(cause: Throwable): Unit | ChannelClosed

sealed trait ChannelClosed
object ChannelClosed:
  case class Error(reason: Option[Exception]) extends ChannelClosed
  case object Done extends ChannelClosed
```

That is, the result of a `safe` operation might be a value, or information that the channel is closed.

Using extensions methods from `ChannelClosedUnion` it's possible to convert such union types to `Either`s, `Try`s or
exceptions, as well as `map` over such resutls.
