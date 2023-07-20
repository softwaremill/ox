package ox.channels

sealed trait ChannelClosed:
  def toException: Exception = this match
    case ChannelClosed.Error(reason) => ChannelClosedException.Error(reason)
    case ChannelClosed.Done          => ChannelClosedException.Done()
object ChannelClosed:
  case class Error(reason: Option[Exception]) extends ChannelClosed
  case object Done extends ChannelClosed

enum ChannelClosedException(reason: Option[Exception]) extends Exception:
  case Error(reason: Option[Exception]) extends ChannelClosedException(reason)
  case Done() extends ChannelClosedException(None)
