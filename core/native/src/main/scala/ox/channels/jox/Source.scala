package ox.channels.jox

trait Source[T] extends CloseableChannel:
  @throws[InterruptedException]
  def receive(): T

  @throws[InterruptedException]
  def receiveOrClosed(): AnyRef

  def tryReceiveOrClosed(): AnyRef

  def receiveClause(): SelectClause[T]

  def receiveClause[U](callback: T => U): SelectClause[U]
