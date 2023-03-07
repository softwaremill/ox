package ox.channels

import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import java.util.concurrent.{ArrayBlockingQueue, ConcurrentLinkedDeque, Semaphore}
import scala.annotation.tailrec
import scala.util.Try

trait Source[+T] extends SourceOps[T]:
  def receive(): ClosedOr[T]

  private[ox] def elementPoll(): T | ChannelState.Closed
  private[ox] def elementPeek(): T | ChannelState.Closed
  private[ox] def cellOffer(c: CellCompleter[T]): Unit
  private[ox] def cellOfferFirst(c: CellCompleter[T]): Unit
  private[ox] def cellCleanup(c: CellCompleter[T]): Unit

object Source extends SourceCompanionOps

trait Sink[-T]:
  def send(t: T): ClosedOr[Unit]

  def error(): ClosedOr[Unit] = error(None)
  def error(reason: Exception): ClosedOr[Unit] = error(Some(reason))
  def error(reason: Option[Exception]): ClosedOr[Unit]

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
  def done(): ClosedOr[Unit]

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
          case null if s == ChannelState.Done => ChannelState.Done
          case null                           => null
          case e                              => e

  override private[ox] def cellOffer(c: CellCompleter[T]): Unit = waiting.offer(c)
  override private[ox] def cellOfferFirst(c: CellCompleter[T]): Unit = waiting.offerFirst(c) // TODO: needed?
  override private[ox] def cellCleanup(c: CellCompleter[T]): Unit = waiting.remove(c)

  // invariant for send & select: either `elements` is empty or `waiting.filter(_.isOwned.get() == false)` is empty

  @tailrec
  private def tryPairWaitingAndElement(): Unit =
    if waiting.peek() != null && elements.peek() != null
    then
      // first trying to dequeue a cell; we might need to put back a new one, and this is only possible for `waiting`, which is a deque
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

  override final def send(t: T): ClosedOr[Unit] =
    closedOrUnit().flatMap { _ =>
      // First, always adding the element to the end of a queue; a previous design "optimised" this by checking
      // if there's a waiting cell, and if so, completing it with the element. However, this could lead to a race
      // condition, where element is delivered out-of-order:
      // T1: selectNow -> null
      // T2: send -> no cells -> add to waiting list
      // T1: offer cell (before elementExists check)
      // T2: send -> complete waiting cell
      //
      // This won't deadlock as always after offering a cell in `select`, we check if there's a waiting element

      // if this is interrupted, the element is simply not added to the queue
      elements.put(t)

      // the channel might have been closed when we were sending, e.g. waiting for free space in `elements`
      state.get() match
        case s @ ChannelState.Error(_) =>
          elements.clear()
          Left(s)
        // if the channel is done, it means that a send() was concurrent with a done() - which can cause the element
        // to be delivered, even if a done was signalled to the receiver before
        case _ =>
          // check if there is a waiting cell
          Right(tryPairWaitingAndElement())
    }

  private def closedOrUnit(): ClosedOr[Unit] = state.get() match
    case ChannelState.Open      => Right(())
    case s: ChannelState.Closed => Left(s)

  @tailrec
  private def drainWaiting(s: ChannelState.Closed): Unit =
    val c = waiting.poll()
    if c != null then
      if c.tryOwn() then c.putClosed(s)
      drainWaiting(s)

  override def receive(): ClosedOr[T] =
    // we could do elements.take(), but then any select() calls would always have priority, as they bypass the elements queue if there's a cell waiting
    select(List(this))

  override def error(reason: Option[Exception]): ClosedOr[Unit] =
    // only first state change from open is valid
    val s = ChannelState.Error(reason)
    if !state.compareAndSet(ChannelState.Open, s) then Left(state.get().asInstanceOf[ChannelState.Closed])
    else
      // not delivering enqueued elements
      elements.clear()
      // completing all waiting cells
      drainWaiting(s)
      Right(())

  override def done(): ClosedOr[Unit] =
    // only first state change from open is valid
    val s = ChannelState.Done
    if !state.compareAndSet(ChannelState.Open, s) then Left(state.get().asInstanceOf[ChannelState.Closed])
    else
      // leaving the elements intact so that they get delivered; but if there are any waiting cells, completing them, as no new elements will arrive
      drainWaiting(s)
      Right(())
