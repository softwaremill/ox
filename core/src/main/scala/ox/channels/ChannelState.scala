package ox.channels

private[ox] sealed trait ChannelState
private[ox] object ChannelState:
  sealed trait Closed extends ChannelState:
    def toResult: ChannelResult.Closed = this match
      case Done     => ChannelResult.Done
      case Error(r) => ChannelResult.Error(r)

  case object Open extends ChannelState
  case class Error(reason: Option[Exception]) extends Closed
  case object Done extends ChannelState with Closed
