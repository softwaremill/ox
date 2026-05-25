package ox.channels.jox

// Ported from: https://github.com/softwaremill/jox/blob/v1.1.2-channels/channels/src/main/java/com/softwaremill/jox/CloseableChannel.java

trait CloseableChannel:
  def done(): Unit
  def doneOrClosed(): AnyRef
  def error(reason: Throwable): Unit
  def errorOrClosed(reason: Throwable): AnyRef

  def isClosedForSend: Boolean = closedForSend() != null
  def isClosedForReceive: Boolean = closedForReceive() != null

  def closedForSend(): ChannelClosed | Null
  def closedForReceive(): ChannelClosed | Null
end CloseableChannel
