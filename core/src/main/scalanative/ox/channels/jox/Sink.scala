package ox.channels.jox

// Ported from: https://github.com/softwaremill/jox/blob/v1.1.2-channels/channels/src/main/java/com/softwaremill/jox/Sink.java

trait Sink[T] extends CloseableChannel:
  @throws[InterruptedException]
  def send(value: T): Unit

  @throws[InterruptedException]
  def sendOrClosed(value: T): AnyRef

  def trySendOrClosed(value: T): AnyRef

  def sendClause(value: T): SelectClause[Null]

  def sendClause[U](value: T, callback: () => U): SelectClause[U]
end Sink
