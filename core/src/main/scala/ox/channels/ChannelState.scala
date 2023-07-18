package ox.channels

private[ox] sealed trait ChannelState
private[ox] object ChannelState:
  sealed trait Closed extends ChannelState:
    def toResult: ChannelClosed = this match
      case Done     => ChannelClosed.Done
      case Error(r) => ChannelClosed.Error(r)

  case object Open extends ChannelState
  case class Error(reason: Option[Exception]) extends Closed
  case object Done extends ChannelState with Closed
