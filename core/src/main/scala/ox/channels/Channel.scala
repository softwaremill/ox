package ox.channels

import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import java.util.concurrent.{ArrayBlockingQueue, ConcurrentLinkedDeque, Semaphore}
import scala.annotation.tailrec
import scala.util.Try

trait Source[+T]:
  def receive(): T
  def tryReceive(): ClosedOr[T] = ClosedOr(receive())

  private[ox] def elementPoll(): T | ChannelState.Closed
  private[ox] def elementPeek(): T | ChannelState.Closed
  private[ox] def cellOffer(c: CellCompleter[T]): Unit
  private[ox] def cellOfferFirst(c: CellCompleter[T]): Unit
  private[ox] def cellCleanup(c: CellCompleter[T]): Unit

trait Sink[-T]:
  def send(t: T): Unit
  def trySend(t: T): ClosedOr[Unit] = ClosedOr(send(t))

  def error(): Unit = error(None)
  def error(reason: Exception): Unit = error(Some(reason))
  def error(reason: Option[Exception]): Unit

  /** Completes the channel with a "done" state.
    *
    * Any elements that have been sent can be received. After that, receivers will learn that the channel is done.
    *
    * No new elements can be sent to this channel. Sending will end with a [[ChannelClosedException.Done]] exception.
    *
    * @note
    *   If a [[send]] is ran concurrently with [[done]], it can happen that a receiver first learns that the channel is done, and then it
    *   can receive the element that was sent concurrently.
    */
  def done(): Unit

class Channel[T](capacity: Int = 1) extends Source[T] with Sink[T]:
  private val elements = ArrayBlockingQueue[T](capacity)
  private val waiting = ConcurrentLinkedDeque[CellCompleter[T]]()
  private val state = AtomicReference[ChannelState](ChannelState.Open)

  override private[ox] def elementPoll(): T | ChannelState.Closed = elementOrClosed(elements.poll())
  override private[ox] def elementPeek(): T | ChannelState.Closed = elementOrClosed(elements.peek())
  private def elementOrClosed(f: => T): T | ChannelState.Closed =
    state.get() match
      case e: ChannelState.Error => e
      case s =>
        f match
          case e                              => e
          case null if s == ChannelState.Done => ChannelState.Done
          case null                           => null

  override private[ox] def cellOffer(c: CellCompleter[T]): Unit = waiting.offer(c)
  override private[ox] def cellOfferFirst(c: CellCompleter[T]): Unit = waiting.offerFirst(c)
  override private[ox] def cellCleanup(c: CellCompleter[T]): Unit = waiting.remove(c)

  // invariant for send & select: either `elements` is empty or `waiting.filter(_.isOwned.get() == false)` is empty

  @tailrec
  private def tryPairWaitingAndElement(): Unit =
    if waiting.peek() != null && elements.peek() != null
    then
      // first trying to dequeue a cell; we might need to put it back, and this is only possible for `waiting`, which is a deque
      // enqueueing back dequeued values into `elements` is not possible as it would break processing ordering
      val c = waiting.poll()
      if c == null then () // somebody already handles the cell - we're done
      else if !c.tryOwn() then tryPairWaitingAndElement() // cell already owned - try again
      else
        // the cell is "ours" - obtaining the element
        val e = elements.poll()
        if e == null then
          // Somebody else already took the element; since this is "our" cell (we completed it), we can be sure that
          // there's somebody waiting on it. Creating a new cell, and completing the old with a reference to it.
          c.putNewCell()
          // Any new elements added while the cell was out of the waiting queue will be paired with cells when the
          // `select` receives the clone
        else c.put(e) // sending the element

  @tailrec
  override final def send(t: T): Unit =
    throwWhenClosed()
    waiting.poll() match
      case null =>
        // if this is interrupted, the element is simply not added to the queue
        try elements.put(t)
        // the channel might have been closed when we were sending, e.g. waiting for free space in `elements`
        finally
          state.get() match
            case s @ ChannelState.Error(_) => elements.clear(); throw s.toException
            // if the channel is done, it means that a send() was concurrent with a done() - which can cause the element
            // to be delivered, even if before a done was signalled to the receiver
            case _ => ()

        // check again, if there is no cell added in the meantime
        tryPairWaitingAndElement()
      case c =>
        if c.tryOwn() then
          // we're the first thread to complete this cell - sending the element
          c.put(t)
        else
          // some other thread already completed the cell - trying to send the element again
          send(t)

  private def throwWhenClosed(): Unit = state.get() match
    case ChannelState.Open      => ()
    case s: ChannelState.Closed => throw s.toException

  @tailrec
  private def drainWaiting(s: ChannelState.Closed): Unit =
    val c = waiting.poll()
    if c != null then
      if c.tryOwn() then c.putClosed(s)
      drainWaiting(s)

  override def receive(): T =
    // we could do elements.take(), but then any select() calls would always have priority, as they bypass the elements queue if there's a cell waiting
    select(List(this))

  override def error(reason: Option[Exception]): Unit =
    // only first state change from open is valid
    val s = ChannelState.Error(reason)
    if !state.compareAndSet(ChannelState.Open, s) then throwWhenClosed()
    // not delivering enqueued elements
    elements.clear()
    // completing all waiting cells
    drainWaiting(s)

  override def done(): Unit =
    // only first state change from open is valid
    val s = ChannelState.Done
    if !state.compareAndSet(ChannelState.Open, s) then throwWhenClosed()
    // leaving the elements intact so that they get delivered; but if there are any waiting cells, completing them, as no new elements will arrive
    drainWaiting(s)
