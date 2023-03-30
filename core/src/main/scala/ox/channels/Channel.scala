package ox.channels

import java.util
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import java.util.concurrent.{ArrayBlockingQueue, ConcurrentLinkedDeque, ConcurrentLinkedQueue, Semaphore}
import scala.annotation.tailrec
import scala.util.Try

trait Source[+T] extends SourceOps[T]:
  def receive(): ClosedOr[T]

  private[ox] def elementPoll(): T | ChannelState.Closed
  private[ox] def elementPeek(): T | ChannelState.Closed
  private[ox] def cellOffer(c: CellCompleter[T]): Unit
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

trait Channel[T] extends Source[T] with Sink[T]:
  override def receive(): ClosedOr[T] =
    // we could do elements.take(), but then any select() calls would always have priority, as they bypass the elements queue if there's a cell waiting
    select(List(this))

/** A channel with capacity 0, requiring that senders & receivers meet to exchange a value. */
class DirectChannel[T] extends Channel[T]:

  /** An element that is waiting for a receiver to be sent directly. */
  private case class PendingElement(element: T):
    private val waiter = new ArrayBlockingQueue[ClosedOr[Unit]](1)
    def waitUntilReceived(): ClosedOr[Unit] = waiter.take()

    /** `receive` or `channelClosed` should be called exactly once by a single thread, after this class is dequeued from `elements` */
    def receive(): T =
      waiter.offer(Right(()))
      element
    def channelClosed(s: ChannelState.Closed): Unit = waiter.offer(Left(s))

  private val elements = ConcurrentLinkedQueue[PendingElement]()
  private val waiting = ConcurrentLinkedQueue[CellCompleter[T]]()
  private val state = CurrentChannelState()

  override private[ox] def elementPoll(): T | ChannelState.Closed = state.elementOrClosed {
    val result = elements.poll()
    if result == null then null.asInstanceOf[T] else result.receive()
  }
  override private[ox] def elementPeek(): T | ChannelState.Closed = state.elementOrClosed {
    val result = elements.peek()
    if result == null then null.asInstanceOf[T] else result.element
  }

  override private[ox] def cellOffer(c: CellCompleter[T]): Unit = waiting.offer(c)
  override private[ox] def cellCleanup(c: CellCompleter[T]): Unit = waiting.remove(c)

  override def send(t: T): ClosedOr[Unit] =
    state.closedOrUnit().flatMap { _ =>
      val pending = PendingElement(t)
      elements.offer(pending)

      tryPairWaitingAndElement(waiting, elements, _.receive())

      // this will return a Left(closed) if the element hasn't been received, but if the channel got closed
      pending.waitUntilReceived()
    }

  @tailrec
  private def drainElements(s: ChannelState.Closed): Unit =
    val pending = elements.poll()
    if pending != null then
      pending.channelClosed(s)
      drainElements(s)

  override def error(reason: Option[Exception]): ClosedOr[Unit] =
    state.error(reason).map { s =>
      // not delivering enqueued elements
      drainElements(s)
      // completing all waiting cells
      drainWaiting(waiting, s)
    }

  override def done(): ClosedOr[Unit] =
    state.done().map { s =>
      // leaving the elements intact so that they get delivered; but if there are any waiting cells, completing them, as no new elements will arrive
      drainWaiting(waiting, s)
    }

class BufferedChannel[T](capacity: Int = 1) extends Channel[T]:
  require(capacity >= 1)

  private val elements = ArrayBlockingQueue[T](capacity)
  private val waiting = ConcurrentLinkedQueue[CellCompleter[T]]()
  private val state = CurrentChannelState()

  override private[ox] def elementPoll(): T | ChannelState.Closed = state.elementOrClosed(elements.poll())
  override private[ox] def elementPeek(): T | ChannelState.Closed = state.elementOrClosed(elements.peek())

  override private[ox] def cellOffer(c: CellCompleter[T]): Unit = waiting.offer(c)
  override private[ox] def cellCleanup(c: CellCompleter[T]): Unit = waiting.remove(c)

  // invariant for send & select: either `elements` is empty or `waiting.filter(_.isOwned.get() == false)` is empty

  override final def send(t: T): ClosedOr[Unit] =
    state.closedOrUnit().flatMap { _ =>
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
          Right(tryPairWaitingAndElement(waiting, elements, identity))
    }

  override def error(reason: Option[Exception]): ClosedOr[Unit] =
    state.error(reason).map { s =>
      // not delivering enqueued elements
      elements.clear()
      // completing all waiting cells
      drainWaiting(waiting, s)
    }

  override def done(): ClosedOr[Unit] =
    state.done().map { s =>
      // leaving the elements intact so that they get delivered; but if there are any waiting cells, completing them, as no new elements will arrive
      drainWaiting(waiting, s)
    }

object Channel:
  def apply[T](capacity: Int = 0): Channel[T] = if capacity == 0 then DirectChannel() else BufferedChannel(capacity)

private class CurrentChannelState:
  private val state = AtomicReference[ChannelState](ChannelState.Open)

  def closedOrUnit(): ClosedOr[Unit] = state.get() match
    case ChannelState.Open      => Right(())
    case s: ChannelState.Closed => Left(s)

  def elementOrClosed[T](f: => T): T | ChannelState.Closed =
    state.get() match
      case e: ChannelState.Error => e
      case s =>
        f match
          case null if s == ChannelState.Done => ChannelState.Done
          case null                           => null
          case e                              => e

  def get(): ChannelState = state.get()

  private def set(s: ChannelState.Closed): ClosedOr[ChannelState.Closed] =
    // only first state change from open is valid
    if !state.compareAndSet(ChannelState.Open, s) then Left(state.get().asInstanceOf[ChannelState.Closed])
    else Right(s)

  def error(reason: Option[Exception]): ClosedOr[ChannelState.Closed] = set(ChannelState.Error(reason))
  def done(): ClosedOr[ChannelState.Closed] = set(ChannelState.Done)

@tailrec
private def tryPairWaitingAndElement[T, U](waiting: util.Queue[CellCompleter[T]], elements: util.Queue[U], unpackElement: U => T): Unit =
  if waiting.peek() != null && elements.peek() != null
  then
    // first trying to dequeue a cell; we might need to put back a new one & suspend the thread again, and this is
    // only possible with cells: enqueueing back dequeued values into `elements` would break processing ordering
    val c = waiting.poll()
    if c == null then () // somebody already handles the cell - we're done
    else if !c.tryOwn() then tryPairWaitingAndElement(waiting, elements, unpackElement) // cell already owned - try again
    else
      // the cell is "ours" - obtaining the element
      val w = elements.poll()
      if w == null then
        // Somebody else already took the element; since this is "our" cell (we completed it), we can be sure that
        // there's somebody waiting on it. Creating a new cell, and completing the old with a reference to it.
        c.completeWithNewCell()
      // Any new elements added while the cell was out of the waiting queue will be paired with cells when the
      // `select` receives the clone
      else c.complete(unpackElement(w)) // sending the element

@tailrec
private def drainWaiting[T](waiting: ConcurrentLinkedQueue[CellCompleter[T]], s: ChannelState.Closed): Unit =
  val c = waiting.poll()
  if c != null then
    if c.tryOwn() then c.completeWithClosed(s)
    drainWaiting(waiting, s)
