package ox.channels.jox

trait Sink[T] extends CloseableChannel:
  @throws[InterruptedException]
  def send(value: T): Unit

  @throws[InterruptedException]
  def sendOrClosed(value: T): AnyRef

  def trySendOrClosed(value: T): AnyRef

  def sendClause(value: T): SelectClause[Null]

  def sendClause[U](value: T, callback: () => U): SelectClause[U]
end Sink
