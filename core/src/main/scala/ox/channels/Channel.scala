package ox.channels

import java.util
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import java.util.concurrent.{ArrayBlockingQueue, ConcurrentLinkedDeque, ConcurrentLinkedQueue, Semaphore}
import java.util.function.Predicate
import scala.annotation.tailrec
import scala.annotation.unchecked.uncheckedVariance
import scala.util.Try

sealed trait ChannelClauseResult[+T]:
  def value: T
  def channel: Source[T] | Sink[_]
//case class DefaultResult[T](v: T) extends ClauseResult[T]

sealed trait ChannelClause[+T]:
  type Result <: ChannelClauseResult[T]
  def channel: Source[T] | Sink[_]

//case class Default[T](v: T) extends Clause[T]:
//  type Result = DefaultResult[T]

trait Source[+T] extends SourceOps[T]:
  // Skipping variance checks here is fine, as the only way a `Received` instance is created is by the original channel,
  // so no values of super-types of T which are not the original T will ever be provided
  case class Received private[channels] (value: T @uncheckedVariance) extends ChannelClauseResult[T]:
    override def channel: Source[T] = Source.this
  case class Receive private[channels] () extends ChannelClause[T]:
    type Result = Received
    override def channel: Source[T] = Source.this

  def receiveClause: Receive = Receive()
  def receive(): ChannelResult[T]

  private[ox] def receiveCellOffer(c: CellCompleter[T]): Unit
  private[ox] def receiveCellCleanup(c: CellCompleter[T]): Unit

  private[ox] def trySatisfyWaiting(): ChannelResult[Unit]

object Source extends SourceCompanionOps

//

trait Sink[-T]:
  case class Sent private[channels] () extends ChannelClauseResult[Unit]:
    override def value: Unit = ()
    override def channel: Sink[T] = Sink.this
  // The Send trait is needed to "hide" the value of type T, so that it's not accessible after construction & casting.
  // Otherwise we could do `val x = Sink[Superclass].Send(); val y: Sink[Subclass#Send] = x`, and then we could access
  // the value through `y`, which is not necessarily of type `Subclass`.
  trait Send extends ChannelClause[Unit]:
    type Result = Sent
    override def channel: Sink[T] = Sink.this

  def sendClause(v: T): Send
  def send(t: T): ChannelResult[Unit]

  def error(): ChannelResult[Unit] = error(None)
  def error(reason: Exception): ChannelResult[Unit] = error(Some(reason))
  def error(reason: Option[Exception]): ChannelResult[Unit]

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
  def done(): ChannelResult[Unit]

  private[ox] def sendCellOffer(v: T, c: CellCompleter[Unit]): Unit
  private[ox] def sendCellCleanup(c: CellCompleter[Unit]): Unit

  private[ox] def trySatisfyWaiting(): ChannelResult[Unit]

//

trait Channel[T] extends Source[T] with Sink[T]:
  override def receive(): ChannelResult[T] = select(List(receiveClause)).map(_.value)
  override def send(v: T): ChannelResult[Unit] = select(List(sendClause(v))).map(_.value)

/** A channel with capacity 0, requiring that senders & receivers meet to exchange a value. */
class DirectChannel[T] extends Channel[T]:

  case class DirectSend(v: T) extends Send
  override def sendClause(v: T): Send = DirectSend(v)

  private val waitingReceives = ConcurrentLinkedQueue[CellCompleter[T]]()
  private val waitingSends = ConcurrentLinkedQueue[(T, CellCompleter[Unit])]()
  private val state = CurrentChannelState()

  override private[ox] def receiveCellOffer(c: CellCompleter[T]): Unit = waitingReceives.offer(c)
  override private[ox] def receiveCellCleanup(c: CellCompleter[T]): Unit = waitingReceives.remove(c)

  override private[ox] def sendCellOffer(v: T, c: CellCompleter[Unit]): Unit = waitingSends.offer((v, c))
  override private[ox] def sendCellCleanup(c: CellCompleter[Unit]): Unit = waitingSends.removeIf((t: (T, CellCompleter[Unit])) => t._2 == c)

  override private[ox] def trySatisfyWaiting(): ChannelResult[Unit] =
    state.asResult() match
      case v @ ChannelResult.Value(()) =>
        while tryPairingSendsAndReceives() do ()
        v
      case d @ ChannelResult.Done =>
        // when the channel is done, we still allow outstanding elements to be delivered
        while tryPairingSendsAndReceives() do ()
        d
      case e: ChannelResult.Error => e

  @tailrec private def ownedWaitingSend(): (T, CellCompleter[Unit]) =
    val cv = waitingSends.poll()
    if cv == null then null
    else if !cv._2.tryOwn() then ownedWaitingSend()
    else cv

  /** @return `true` if a send was paired up with a `receive` */
  @tailrec private def tryPairingSendsAndReceives(): Boolean =
    if waitingReceives.peek() != null && waitingSends.peek() != null
    then
      val c = waitingReceives.poll()
      if c == null then false // somebody already handles the cell that we peeked at - we're done, no more cells
      else if !c.tryOwn() then tryPairingSendsAndReceives() // cell already owned - try again
      else
        val cv2 = ownedWaitingSend()
        if cv2 == null then
          // somebody else already took the waiting send off the queue - creating a new cell as `c` is already used up
          c.completeWithNewCell()
          false // no more sends to pair up with
        else
          // both cells are "ours"
          c.complete(Received(cv2._1))
          cv2._2.complete(Sent())
          true
    else false

  override def error(reason: Option[Exception]): ChannelResult[Unit] =
    state.error(reason).map { s =>
      // completing all waiting cells
      drainWaiting(waitingReceives, identity, s)
      drainWaiting(waitingSends, _._2, s)
    }

  override def done(): ChannelResult[Unit] =
    state.done().map { s =>
      // we need a special method to drain `waitingReceives` to handle the cases when `done()` is called concurrently
      // with `select(receive)`, so that any non-satisfiable receives are completed with a `Done`
      drainWaitingReceivesWhenDone()

      drainWaiting(waitingSends, _._2, s)
    }

  @tailrec private def drainWaitingReceivesWhenDone(): Unit =
    val c = waitingReceives.poll()
    if c == null then () // no more receives
    else if !c.tryOwn() then drainWaitingReceivesWhenDone() // cell already owned - continue with next
    else
      val cv2 = ownedWaitingSend()
      if cv2 == null
      then c.completeWithClosed(ChannelState.Done) // no more elements - completing the cell with `Done`
      else
        c.complete(Received(cv2._1))
        cv2._2.complete(Sent())
      drainWaitingReceivesWhenDone()

class BufferedChannel[T](capacity: Int = 1) extends Channel[T]:
  require(capacity >= 1)

  case class BufferedSend(v: T) extends Send
  override def sendClause(v: T): Send = BufferedSend(v)

  private val elements = ArrayBlockingQueue[T](capacity)
  private val waitingReceives = ConcurrentLinkedQueue[CellCompleter[T]]()
  private val waitingSends = ConcurrentLinkedQueue[(T, CellCompleter[Unit])]()
  private val state = CurrentChannelState()

  override private[ox] def receiveCellOffer(c: CellCompleter[T]): Unit = waitingReceives.offer(c)
  override private[ox] def receiveCellCleanup(c: CellCompleter[T]): Unit = waitingReceives.remove(c)

  override private[ox] def sendCellOffer(v: T, c: CellCompleter[Unit]): Unit = waitingSends.offer((v, c))
  override private[ox] def sendCellCleanup(c: CellCompleter[Unit]): Unit = waitingSends.removeIf((t: (T, CellCompleter[Unit])) => t._2 == c)

  // TODO invariant for send & select: either `elements` is empty or `waiting.filter(_.isOwned.get() == false)` is empty

  override private[ox] def trySatisfyWaiting(): ChannelResult[Unit] =
    state.asResult() match
      case v @ ChannelResult.Value(()) =>
        // any receive must be followed by an attempt to satisfy a send, and vice versa; an invocation of `trySatisfyWaiting()`
        // from another thread might end up being a no-op, as the `elements` queue is full/empty, but because of concurrently
        // running `select()`s, we might have both a pending receive/send that can be satisfied
        while trySatisfyWaitingReceives() | trySatisfyWaitingSends() do () // important: a non-short-circuiting or
        v
      case d @ ChannelResult.Done =>
        // when the channel is done, we still allow outstanding elements to be delivered; no new elements can be sent, though
        // we have to try satisfying receives as long as there are any waiting ones, as the `trySatisfyWaiting()` calls for
        // other `select()`s might be scheduled later
        while trySatisfyWaitingReceives() do ()
        d
      case e: ChannelResult.Error => e

  /** @return `true` if a value was taken off the `elements` queue */
  @tailrec private def trySatisfyWaitingReceives(): Boolean =
    if waitingReceives.peek() != null && elements.peek() != null
    then
      // first trying to dequeue a cell; we might need to put back a new one & suspend the thread again, and this is
      // only possible with cells: enqueueing back dequeued values into `elements` would break processing ordering
      val c = waitingReceives.poll()
      if c == null then false // somebody already handles the cell that we peeked at - we're done, no more cells
      else if !c.tryOwn() then trySatisfyWaitingReceives() // cell already owned - try again
      else
        // the cell is "ours" - obtaining the element
        val w = elements.poll()
        if w == null then
          // Somebody else already took the element; since this is "our" cell (we completed it), we can be sure that
          // there's somebody waiting on it. Creating a new cell, and completing the old with a reference to it.
          c.completeWithNewCell()
          // Any new elements added while the cell was out of the waiting queue will be paired with cells when the
          // `select` receives the clone
          false
        else
          c.complete(Received(w)) // sending the element
          true
    else false

  /** @return `true` if a value was added to the `elements` queue */
  @tailrec private def trySatisfyWaitingSends(): Boolean =
    // the algorithm is similar as with trySatisfyWaitingReceives()
    if waitingSends.peek() != null && elements.remainingCapacity() > 0
    then
      val cv = waitingSends.poll()
      if cv == null then false // somebody already handles the cell that we peeked at - we're done, no more cells
      else if !cv._2.tryOwn() then trySatisfyWaitingSends() // cell already owned - try again
      // trying to append the element to the queue in a non-blocking way
      else if elements.offer(cv._1) then
        cv._2.complete(Sent())
        true
      else
        cv._2.completeWithNewCell()
        false
    else false

  override def error(reason: Option[Exception]): ChannelResult[Unit] =
    state.error(reason).map { s =>
      // not delivering enqueued elements
      elements.clear()
      // completing all waiting cells
      drainWaiting(waitingReceives, identity, s)
      drainWaiting(waitingSends, _._2, s)
    }

  override def done(): ChannelResult[Unit] =
    state.done().map { s =>
      // leaving the elements intact so that they get delivered

      // we need a special method to drain `waitingReceives` to handle the cases when `done()` is called concurrently
      // with `select(receive)`; this method satisfies any waiting receives with pending elements, if they are available
      drainWaitingReceivesWhenDone()

      drainWaiting(waitingSends, _._2, s)
    }

  @tailrec private def drainWaitingReceivesWhenDone(): Unit =
    val c = waitingReceives.poll()
    if c == null then () // no more receives
    else if !c.tryOwn() then drainWaitingReceivesWhenDone() // cell already owned - continue with next
    else
      val w = elements.poll()
      if w == null
      then c.completeWithClosed(ChannelState.Done) // no more elements - completing the cell with `Done`
      else c.complete(Received(w)) // sending the element
      drainWaitingReceivesWhenDone()

object Channel:
  def apply[T](capacity: Int = 0): Channel[T] = if capacity == 0 then DirectChannel() else BufferedChannel(capacity)

private class CurrentChannelState:
  private val state = AtomicReference[ChannelState](ChannelState.Open)

  def asResult(): ChannelResult[Unit] = state.get() match
    case ChannelState.Open     => ChannelResult.Value(())
    case ChannelState.Done     => ChannelResult.Done
    case ChannelState.Error(r) => ChannelResult.Error(r)

  def elementOrClosed[T](f: => T): T | ChannelState.Closed =
    state.get() match
      case e: ChannelState.Error => e
      case s =>
        f match
          case null if s == ChannelState.Done => ChannelState.Done
          case null                           => null
          case e                              => e

  def get(): ChannelState = state.get()

  private def set(s: ChannelState.Closed): ChannelResult[ChannelState.Closed] =
    // only first state change from open is valid
    if !state.compareAndSet(ChannelState.Open, s) then state.get().asInstanceOf[ChannelState.Closed].toResult
    else ChannelResult.Value(s)

  def error(reason: Option[Exception]): ChannelResult[ChannelState.Closed] = set(ChannelState.Error(reason))
  def done(): ChannelResult[ChannelState.Closed] = set(ChannelState.Done)

@tailrec
private def drainWaiting[T](waiting: ConcurrentLinkedQueue[T], toCell: T => CellCompleter[_], s: ChannelState.Closed): Unit =
  val c = waiting.poll()
  if c != null then
    val cell = toCell(c)
    if cell.tryOwn() then cell.completeWithClosed(s)
    drainWaiting(waiting, toCell, s)
