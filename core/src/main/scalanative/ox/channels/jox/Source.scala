package ox.channels.jox

// Ported from: https://github.com/softwaremill/jox/blob/v1.1.2-channels/channels/src/main/java/com/softwaremill/jox/Source.java

trait Source[T] extends CloseableChannel:
  @throws[InterruptedException]
  def receive(): T

  @throws[InterruptedException]
  def receiveOrClosed(): AnyRef

  def tryReceiveOrClosed(): AnyRef

  def receiveClause(): SelectClause[T]

  def receiveClause[U](callback: T => U): SelectClause[U]
end Source
