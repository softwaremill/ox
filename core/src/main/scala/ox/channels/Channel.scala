package ox.channels

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.{ArrayBlockingQueue, ConcurrentLinkedDeque, Semaphore}
import scala.annotation.tailrec

trait Source[+T]:
  def receive(): T

  private[ox] def elementPoll(): T
  private[ox] def elementPeek(): T
  private[ox] def cellOffer(w: CellCompleter[T]): Unit

trait Sink[-T]:
  def send(t: T): Unit

class Channel[T](capacity: Int = 1) extends Source[T] with Sink[T]:
  private val elements = ArrayBlockingQueue[T](capacity)
  // TODO: if we continuously select from a channel, to which there are no sends, the completers will accumulate
  private val waiting = ConcurrentLinkedDeque[CellCompleter[T]]()

  override private[ox] def elementPoll(): T = elements.poll()
  override private[ox] def elementPeek(): T = elements.peek()
  override private[ox] def cellOffer(w: CellCompleter[T]): Unit = waiting.offer(w)

  @tailrec
  final def send(t: T): Unit =
    @tailrec
    def tryPairWaitingAndElement(): Unit =
      if waiting.peek() != null && elements.peek() != null
      then
        val c = waiting.poll()
        if c == null
        then
          // somebody already took the waiter - we're done
          ()
        else if !c.tryOwn()
        then
          // waiter already done - try again
          tryPairWaitingAndElement()
        else
          // the waiter is "ours" - obtaining the element
          val e = elements.poll()
          if e == null then
            // somebody else already took the element, putting back the waiter
            // since this is "our" waiter (we completed it), we can be sure that there's somebody waiting on it
            // hence, putting back a waiter with the same cell, but a new "complete" flag
            waiting.offerFirst(c.cloneNotOwned)
            // a new element might have been added, while the waiter was taken off the queue
            tryPairWaitingAndElement()
          else
            // sending the element
            c.complete(e)
      else ()

    waiting.poll() match
      case null =>
        // TODO: when interrupted?
        elements.put(t)
        // check again, if there is no waiter added in the meantime
        tryPairWaitingAndElement()
      case c =>
        if c.tryOwn() then
          // we're the first thread to complete this waiter - sending the element
          c.complete(t)
        else
          // some other thread already completed the waiter - trying to send the element again
          send(t)

  def receive(): T =
    // we could do elements.take(), but then any select() calls would always have priority, as they bypass the elements queue if there's a cell waiting
    select(List(this))

private[ox] trait CellCompleter[-T]:
  /** Should only be called if this cell is owned by the calling thread. */
  def complete(t: T): Unit

  /** If `true`, the calling thread becomes the owner of the waiter, and has to complete the cell with an element. */
  def tryOwn(): Boolean

  /** Create a copy of this cell, which isn't yet owned by any thread. Should only be called if this cell is owned by the calling thread. */
  def cloneNotOwned: CellCompleter[T]

private[ox] class Cell[T](cell: ArrayBlockingQueue[T]) extends CellCompleter[T]:
  def this() = this(new ArrayBlockingQueue[T](1))

  private val isDone = new AtomicBoolean(false)
  def complete(t: T): Unit = cell.put(t)
  def await(): T = cell.take()
  def tryOwn(): Boolean = isDone.compareAndSet(false, true)
  def cloneNotOwned: Cell[T] = Cell[T](cell)

def select[T1, T2](ch1: Source[T1], ch2: Source[T2]): T1 | T2 = select(List(ch1, ch2))

// invariant: either elements is non-empty or waiting.filter(notComplete) is non-empty (or both)
@tailrec
def select[T](channels: List[Source[T]]): T = {
  @tailrec
  def takeFirst(chs: List[Source[T]]): Option[T] =
    chs match
      case Nil => None
      case ch :: tail =>
        val e = ch.elementPoll()
        if e != null then Some(e) else takeFirst(tail)

  takeFirst(channels) match
    case Some(e) => e
    case None    =>
      // none of the channels has an available element - enqueue a waiter on each channel's waiting list
      val c = Cell[T]
      channels.foreach(_.cellOffer(c))

      // check, if no new element has arrived in the meantime (possibly, before we added the waiter)
      if channels.exists(_.elementPeek() != null) then
        // some element arrived in the meantime: trying to invalidate the waiter
        if c.tryOwn() then
          // we managed to complete the waiter before any other thread - try again to obtain an element
          // we are sure that there's nobody waiting on this waiter, as this could only be us
          select(channels)
        else
          // some other thread already completed the waiter - receiving the element
          c.await()
      else
        // still no new elements - waiting for one to arrive
        c.await()
}

/*

To consider: waiting getting interrupted & completed at the same time -> catch interrupted exception,
try setting "done", if failed -> some sender already completed, try receiving, interrupt self, continue as normal

 */
