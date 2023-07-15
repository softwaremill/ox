package ox.channels

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

private[ox] trait CellCompleter[-T]:
  /** Complete the cell with a value. Should only be called if this cell is owned by the calling thread. */
  def complete(t: ChannelClauseResult[T]): Unit

  /** Complete the cell with a new completer. Should only be called if this cell is owned by the calling thread. */
  def completeWithNewCell(): Unit

  /** Complete the cell indicating that the channel is closed (error or done). Should only be called if this cell is owned by the calling
    * thread.
    */
  def completeWithClosed(s: ChannelState.Closed): Unit

  /** If `true`, the calling thread becomes the owner of the cell, and has to complete the cell using one of the `complete` methods. */
  def tryOwn(): Boolean

private[ox] class Cell[T] extends CellCompleter[T]:
  private val isOwned = new AtomicBoolean(false)
  private val cell = new ArrayBlockingQueue[ChannelClauseResult[T] | Cell[T] | ChannelState.Closed](1)

  // each cell should be completed exactly once, so we are not using the blocking capabilities of `cell`;
  // using `cell.put` might throw an interrupted exception, which might cause a deadlock (as there's a thread awaiting a
  // cell's completion on its own interrupt - see cellTakeInterrupted); hence, using `.add`.
  override def complete(t: ChannelClauseResult[T]): Unit = cell.add(t)
  override def completeWithNewCell(): Unit = cell.add(Cell[T])
  override def completeWithClosed(s: ChannelState.Closed): Unit = cell.add(s)
  override def tryOwn(): Boolean = isOwned.compareAndSet(false, true)
  def take(): ChannelClauseResult[T] | Cell[T] | ChannelState.Closed = cell.take()
