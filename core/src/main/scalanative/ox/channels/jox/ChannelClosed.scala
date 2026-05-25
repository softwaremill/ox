package ox.channels.jox

// Ported from: https://github.com/softwaremill/jox/blob/v1.1.2-channels/channels/src/main/java/com/softwaremill/jox/ChannelClosed.java
//              https://github.com/softwaremill/jox/blob/v1.1.2-channels/channels/src/main/java/com/softwaremill/jox/ChannelDone.java
//              https://github.com/softwaremill/jox/blob/v1.1.2-channels/channels/src/main/java/com/softwaremill/jox/ChannelError.java
//              https://github.com/softwaremill/jox/blob/v1.1.2-channels/channels/src/main/java/com/softwaremill/jox/ChannelClosedException.java

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
