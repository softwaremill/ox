package ox.channels

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import scala.annotation.tailrec

// a lazily-created, optional result - exceptions might be throw when the function is called, hence it should be called
// only on the thread where the value should be received
private[ox] type MaybeCreateResult[T] = () => Option[SelectResult[T]]

private[ox] trait CellCompleter[-T]:
  /** Complete the cell with a result. Should only be called if this cell is owned by the calling thread. */
  def complete(t: SelectResult[T]): Unit

  /** Complete the cell with a lazily-created, optional result. Should only be called if this cell is owned by the calling thread. */
  def complete(t: MaybeCreateResult[T]): Unit

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
  private val cell = new ArrayBlockingQueue[SelectResult[T] | MaybeCreateResult[T] | Cell[T] | ChannelState.Closed](1)

  // each cell should be completed exactly once, so we are not using the blocking capabilities of `cell`;
  // using `cell.put` might throw an interrupted exception, which might cause a deadlock (as there's a thread awaiting a
  // cell's completion on its own interrupt - see cellTakeInterrupted); hence, using `.add`.
  override def complete(t: SelectResult[T]): Unit = cell.add(t)
  override def complete(t: MaybeCreateResult[T]): Unit = cell.add(t)
  override def completeWithNewCell(): Unit = cell.add(Cell[T])
  override def completeWithClosed(s: ChannelState.Closed): Unit = cell.add(s)
  override def tryOwn(): Boolean = isOwned.compareAndSet(false, true)
  def take(): SelectResult[T] | MaybeCreateResult[T] | Cell[T] | ChannelState.Closed = cell.take()
  def isAlreadyOwned: Boolean = isOwned.get()

/** Linked cells are created when creating CollectSources. */
private[ox] class LinkedCell[T, U](val linkedTo: CellCompleter[U], f: T => Option[U], createReceived: U => Source[U]#Received)
    extends CellCompleter[T]:
  override def complete(t: SelectResult[T]): Unit =
    t match
      case r: Source[T]#Received => linkedTo.complete(() => f(r.value).map(createReceived)) // f might throw exceptions, making lazy
      case _                     => throw new IllegalStateException() // linked cells can only be created from sources
  override def complete(t: MaybeCreateResult[T]): Unit =
    linkedTo.complete { () =>
      t() match
        case Some(r: Source[T]#Received) => f(r.value).map(createReceived)
        case Some(_)                     => throw new IllegalStateException() // linked cells can only be created from sources
        case _                           => None
    }
  override def completeWithNewCell(): Unit = linkedTo.completeWithNewCell()
  override def completeWithClosed(s: ChannelState.Closed): Unit = linkedTo.completeWithClosed(s)
  override def tryOwn(): Boolean = linkedTo.tryOwn()

@tailrec
private[ox] def sameCell(c1: CellCompleter[_], c2: CellCompleter[_]): Boolean =
  c1 match
    case l1: LinkedCell[_, _] => sameCell(l1.linkedTo, c2)
    case _ =>
      c2 match
        case l2: LinkedCell[_, _] => sameCell(c1, l2.linkedTo)
        case _                    => c1 == c2
