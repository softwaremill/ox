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

  // invariant for send & select: either elements is empty or waiting.filter(notComplete) is empty

  @tailrec
  final def send(t: T): Unit =
    @tailrec
    def tryPairWaitingAndElement(): Unit =
      if waiting.peek() != null && elements.peek() != null
      then
        // first trying to dequeue a cell; we might need to put it back, and this is only possible for `waiting`,
        // which is a deque
        // enqueueing back dequeued values into `elements` is not possible as it would break processing ordering
        val c = waiting.poll()
        if c == null
        then
          // somebody already owned the cell - we're done
          ()
        else if !c.tryOwn()
        then
          // cell already owned - try again
          tryPairWaitingAndElement()
        else
          // the cell is "ours" - obtaining the element
          val e = elements.poll()
          if e == null then
            // Somebody else already took the element; since this is "our" cell (we completed it), we can be sure that
            // there's somebody waiting on it. Creating a new cell, and completing the old with a reference to it.
            val c2 = c.putClone
            waiting.offerFirst(c2)
            // a new element might have been added, while the cell was taken off the queue
            tryPairWaitingAndElement()
          else
            // sending the element
            c.put(e)
      else ()

    waiting.poll() match
      case null =>
        // if this is interrupted, the element is simply not added to the queue
        elements.put(t)

        // check again, if there is no cell added in the meantime
        tryPairWaitingAndElement()
      case c =>
        if c.tryOwn() then
          // we're the first thread to complete this cell - sending the element
          c.put(t)
        else
          // some other thread already completed the cell - trying to send the element again
          send(t)

  def receive(): T =
    // we could do elements.take(), but then any select() calls would always have priority, as they bypass the elements queue if there's a cell waiting
    select(List(this))

private[ox] trait CellCompleter[-T]:
  /** Complete the cell with a value. Should only be called if this cell is owned by the calling thread. */
  def put(t: T): Unit

  /** Complete the cell with a new completer, and return it.. Should only be called if this cell is owned by the calling thread. */
  def putClone: CellCompleter[T]

  /** If `true`, the calling thread becomes the owner of the cell, and has to complete the cell with an element. */
  def tryOwn(): Boolean

private[ox] class Cell[T] extends CellCompleter[T]:
  private val isDone = new AtomicBoolean(false)
  private val cell = new ArrayBlockingQueue[T | Cell[T]](1)

  def put(t: T): Unit = cell.put(t)
  def putClone: CellCompleter[T] =
    val c2 = Cell[T]
    cell.put(c2)
    c2
  def take(): T | Cell[T] = cell.take()
  def tryOwn(): Boolean = isDone.compareAndSet(false, true)

def select[T1, T2](ch1: Source[T1], ch2: Source[T2]): T1 | T2 = select(List(ch1, ch2))

@tailrec
def select[T](channels: List[Source[T]]): T =
  @tailrec
  def pollFirst(chs: List[Source[T]]): Option[T] =
    chs match
      case Nil => None
      case ch :: tail =>
        val e = ch.elementPoll()
        if e != null then Some(e) else pollFirst(tail)

  def takeFromCellInterruptSafe(c: Cell[T]): T =
    try
      c.take() match
        case c2: Cell[T] => takeFromCellInterruptSafe(c2)
        case t: T        => t
    catch
      case e: InterruptedException =>
        @tailrec
        def ownAndInterruptSelf(cc: Cell[T]): T =
          // trying to invalidate the cell by owning it
          if cc.tryOwn() then
            // nobody else will complete the cell, we can re-throw the exception
            throw e
          else
            // somebody else completed the cell; might block, but event if, for a short period of time, as the
            // cell-owning thread should complete it without blocking
            cc.take() match
              // a new cell which we can try to own
              case cc2: Cell[T] => ownAndInterruptSelf(cc2)
              // received the element; interrupting self and returning it
              case t: T =>
                try t
                finally Thread.currentThread().interrupt()

        ownAndInterruptSelf(c)

  pollFirst(channels) match
    case Some(e) => e
    case None    =>
      // none of the channels has an available element - enqueue a cell on each channel's waiting list
      val c = Cell[T]
      channels.foreach(_.cellOffer(c))

      // check, if no new element has arrived in the meantime (possibly, before we added the cell)
      if channels.exists(_.elementPeek() != null) then
        // some element arrived in the meantime: trying to invalidate the cell by owning it
        if c.tryOwn() then
          // we managed to complete the cell before any other thread - try again to obtain an element
          // we are sure that there's nobody waiting on this cell, as this could only be us
          select(channels)
        else
          // some other thread already completed the cell - receiving the element
          takeFromCellInterruptSafe(c)
      else
        // still no new elements - waiting for one to arrive
        takeFromCellInterruptSafe(c)
