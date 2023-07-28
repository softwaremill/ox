package ox.channels

sealed trait ChannelClosed:
  def toThrowable: Throwable = this match
    case ChannelClosed.Error(reason) => ChannelClosedException.Error(reason)
    case ChannelClosed.Done          => ChannelClosedException.Done()
object ChannelClosed:
  case class Error(reason: Option[Throwable]) extends ChannelClosed
  case object Done extends ChannelClosed

enum ChannelClosedException(reason: Option[Throwable]) extends Exception:
  case Error(reason: Option[Throwable]) extends ChannelClosedException(reason)
  case Done() extends ChannelClosedException(None)
