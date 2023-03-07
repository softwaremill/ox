package ox.channels

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

private[ox] trait CellCompleter[-T]:
  /** Complete the cell with a value. Should only be called if this cell is owned by the calling thread. */
  def put(t: T): Unit

  /** Complete the cell with a new completer. Should only be called if this cell is owned by the calling thread. */
  def putNewCell(): Unit

  /** Complete the cell indicating that the channel is closed (error or done). Should only be called if this cell is owned by the calling
    * thread.
    */
  def putClosed(s: ChannelState.Closed): Unit

  /** If `true`, the calling thread becomes the owner of the cell, and has to complete the cell using one of the `put` methods. */
  def tryOwn(): Boolean

private[ox] class Cell[T] extends CellCompleter[T]:
  private val isOwned = new AtomicBoolean(false)
  private val cell = new ArrayBlockingQueue[T | Cell[T] | ChannelState.Closed](1)

  // there will ever only be one .put invocation, and it will never block; using cell.put might throw an interrupted exception,
  // which might cause a deadlock (as there's a thread awaiting a cell's completion on its own interrupt); hence, using .add
  override def put(t: T): Unit = cell.add(t)
  override def putNewCell(): Unit = cell.add(Cell[T])
  override def putClosed(s: ChannelState.Closed): Unit = cell.add(s)
  override def tryOwn(): Boolean = isOwned.compareAndSet(false, true)
  def take(): T | Cell[T] | ChannelState.Closed = cell.take()
