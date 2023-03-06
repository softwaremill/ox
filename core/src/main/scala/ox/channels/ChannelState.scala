package ox.channels

sealed trait ChannelState
object ChannelState:
  sealed trait Closed extends ChannelState:
    def toException: Exception = this match
      case ChannelState.Error(reason) => ChannelClosedException.Error(reason)
      case ChannelState.Done          => ChannelClosedException.Done()

  case object Open extends ChannelState
  case class Error(reason: Option[Exception]) extends Closed
  case object Done extends ChannelState with Closed

enum ChannelClosedException(reason: Option[Exception]) extends Exception:
  case Error(reason: Option[Exception]) extends ChannelClosedException(reason)
  case Done() extends ChannelClosedException(None)

type ClosedOr[T] = Either[ChannelState.Closed, T]

extension [T](c: ClosedOr[T]) {
  def orThrow: T = c match
    case Left(s)      => throw s.toException
    case Right(value) => value
}
