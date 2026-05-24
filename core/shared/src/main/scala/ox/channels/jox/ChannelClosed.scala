package ox.channels.jox

sealed trait ChannelClosed:
  def toException(): ChannelClosedException
  def channel: Channel[?]

case class ChannelDone(override val channel: Channel[?]) extends ChannelClosed:
  override def toException(): ChannelClosedException = new ChannelDoneException()

case class ChannelError(cause: Throwable, override val channel: Channel[?]) extends ChannelClosed:
  override def toException(): ChannelClosedException = new ChannelErrorException(cause)

sealed class ChannelClosedException(cause: Throwable) extends RuntimeException(cause):
  def this() = this(null)

final class ChannelDoneException extends ChannelClosedException()
final class ChannelErrorException(cause: Throwable) extends ChannelClosedException(cause)
