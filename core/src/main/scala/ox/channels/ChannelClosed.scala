package ox.channels

import com.softwaremill.jox.{ChannelDone as JChannelDone, ChannelError as JChannelError}

/** Returned by channel methods (e.g. [[Source.receiveOrClosed]], [[Sink.sendOrClosed]], [[selectOrClosed]]) when the channel is closed. */
sealed trait ChannelClosed:
  def toThrowable: Throwable = this match
    case ChannelClosed.Error(reason) => ChannelClosedException.Error(reason)
    case ChannelClosed.Done          => ChannelClosedException.Done()

object ChannelClosed:
  case class Error(reason: Throwable) extends ChannelClosed
  case object Done extends ChannelClosed

  private[ox] def fromJoxOrT[T](joxResult: AnyRef): T | ChannelClosed = fromJox(joxResult).asInstanceOf[T | ChannelClosed]
  private[ox] def fromJoxOrUnit(joxResult: AnyRef): Unit | ChannelClosed =
    if joxResult == null then () else fromJox(joxResult).asInstanceOf[ChannelClosed]

  /** Converts the result of jox's `tryReceiveOrClosed()` (which returns `T | JChannelClosed | null`) to `Option[T] | ChannelClosed`. `null`
    * means no value was immediately available.
    */
  private[ox] def fromJoxTryReceiveOrClosed[T](joxResult: AnyRef): Option[T] | ChannelClosed =
    joxResult match
      case null             => None
      case _: JChannelDone  => Done
      case e: JChannelError => Error(e.cause())
      case v                => Some(v.asInstanceOf[T])

  /** Converts the result of jox's `trySendOrClosed()` (which returns `null | JChannelClosed | sentinel`) to `Boolean | ChannelClosed`.
    * `null` means the value was sent, any other non-`ChannelClosed` value (sentinel) means it was not sent.
    */
  private[ox] def fromJoxTrySendOrClosed(joxResult: AnyRef): Boolean | ChannelClosed =
    joxResult match
      case null             => true
      case _: JChannelDone  => Done
      case e: JChannelError => Error(e.cause())
      case _                => false

  private def fromJox(joxResult: AnyRef): AnyRef | ChannelClosed =
    joxResult match
      case _: JChannelDone  => Done
      case e: JChannelError => Error(e.cause())
      case _                => joxResult
end ChannelClosed

enum ChannelClosedException(cause: Option[Throwable]) extends Exception(cause.orNull):
  case Error(cause: Throwable) extends ChannelClosedException(Some(cause))
  case Done() extends ChannelClosedException(None)
