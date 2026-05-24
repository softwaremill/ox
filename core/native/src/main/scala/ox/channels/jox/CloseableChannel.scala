package ox.channels.jox

trait CloseableChannel:
  def done(): Unit
  def doneOrClosed(): AnyRef
  def error(reason: Throwable): Unit
  def errorOrClosed(reason: Throwable): AnyRef

  def isClosedForSend: Boolean = closedForSend() != null
  def isClosedForReceive: Boolean = closedForReceive() != null

  def closedForSend(): ChannelClosed | Null
  def closedForReceive(): ChannelClosed | Null
