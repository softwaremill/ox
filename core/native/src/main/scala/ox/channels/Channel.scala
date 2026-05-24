package ox.channels

import ox.channels.{jox => j}
import ox.channels.jox.{Channel as JChannel, Select as JSelect, SelectClause as JSelectClause, Sink as JSink, Source as JSource}

import ChannelClosedUnion.orThrow

import scala.annotation.unchecked.uncheckedVariance

// select result: needs to be defined here, as implementations are defined here as well

/** Results of a [[select]] call, when clauses are passed (instead of a number of [[Source]]s). Each result corresponds to a clause, and can
  * be pattern-matched (using a path-dependent type) to inspect which clause was selected.
  */
sealed trait SelectResult[+T]:
  def value: T

/** The result returned in case a [[Default]] clause was selected in [[select]]. */
case class DefaultResult[T](value: T) extends SelectResult[T]

// select clauses: needs to be defined here, as implementations are defined here as well

/** A clause to use as part of [[select]]. Clauses can be created having a channel instance, using [[Source.receiveClause]] and
  * [[Sink.sendClause]].
  *
  * A clause instance is immutable and can be reused in multiple [[select]] calls.
  */
sealed trait SelectClause[+T]:
  private[ox] def delegate: JSelectClause[Any]
  type Result <: SelectResult[T]

/** A default clause, which will be chosen if no other clause can be selected immediately, during a [[select]] call.
  *
  * There should be at most one default clause, and it should always come last in the list of clauses.
  */
case class Default[T](value: T) extends SelectClause[T]:
  override private[ox] def delegate: JSelectClause[Any] = JSelect.defaultClause(() => DefaultResult(value))
  type Result = DefaultResult[T]

//

/** A channel source, which can be used to receive values from the channel. See [[Channel]] for more details. */
trait Source[+T] extends SourceOps[T] with SourceDrainOps[T]:
  protected def delegate: JSource[Any]

  case class Received private[channels] (value: T @uncheckedVariance) extends SelectResult[T]

  case class Receive private[channels] (delegate: JSelectClause[Any]) extends SelectClause[T]:
    type Result = Received

  def receiveClause: Receive = Receive(delegate.receiveClause(t => Received(t.asInstanceOf[T])))

  def tryReceive(): Option[T] = tryReceiveOrClosed().orThrow

  def tryReceiveOrClosed(): Option[T] | ChannelClosed =
    val r = delegate.tryReceiveOrClosed()
    if r == null then None
    else
      ChannelClosed.fromJox(r.asInstanceOf[AnyRef]) match
        case c: ChannelClosed => c
        case v: T @unchecked  => Some(v)

  def receiveOrClosed(): T | ChannelClosed = ChannelClosed.fromJoxOrT(delegate.receiveOrClosed())

  def receiveOrDone(): T | ChannelClosed.Done.type = receiveOrClosed() match
    case e: ChannelClosed.Error => throw e.toThrowable
    case ChannelClosed.Done     => ChannelClosed.Done
    case t: T @unchecked        => t

  def receive(): T = receiveOrClosed().orThrow

  def isClosedForReceive: Boolean = delegate.isClosedForReceive

  def isClosedForReceiveDetail: Option[ChannelClosed] = Option(ChannelClosed.fromJoxOrT(delegate.closedForReceive()))
end Source

object Source extends SourceCompanionOps

//

/** A channel sink, which can be used to send values to the channel. See [[Channel]] for more details. */
trait Sink[-T]:
  protected def delegate: JSink[Any]

  case class Sent private[channels] () extends SelectResult[Unit]:
    override def value: Unit = ()

  case class Send private[channels] (delegate: JSelectClause[Any]) extends SelectClause[Unit]:
    type Result = Sent

  def sendClause(t: T): Send = Send(delegate.asInstanceOf[JSink[T]].sendClause(t, () => Sent()))

  def trySend(t: T): Boolean = trySendOrClosed(t).orThrow

  def trySendOrClosed(t: T): Boolean | ChannelClosed =
    val r = delegate.asInstanceOf[JSink[T]].trySendOrClosed(t)
    if r == null then true
    else
      ChannelClosed.fromJox(r.asInstanceOf[AnyRef]) match
        case c: ChannelClosed => c
        case _                => false

  def sendOrClosed(t: T): Unit | ChannelClosed =
    val r = ChannelClosed.fromJoxOrUnit(delegate.asInstanceOf[JSink[T]].sendOrClosed(t))
    if r == null then () else r

  def send(t: T): Unit = sendOrClosed(t).orThrow

  def errorOrClosed(reason: Throwable): Unit | ChannelClosed = ChannelClosed.fromJoxOrUnit(delegate.errorOrClosed(reason))

  def error(reason: Throwable): Unit = errorOrClosed(reason).orThrow

  def doneOrClosed(): Unit | ChannelClosed = ChannelClosed.fromJoxOrUnit(delegate.doneOrClosed())

  def done(): Unit = doneOrClosed().orThrow

  def isClosedForSend: Boolean = delegate.isClosedForSend

  def isClosedForSendDetail: Option[ChannelClosed] = Option(ChannelClosed.fromJoxOrT(delegate.closedForSend()))
end Sink

//

class Channel[T] private (capacity: Int) extends Source[T] with Sink[T]:
  protected override val delegate: JChannel[Any] = capacity match
    case 0  => JChannel.newRendezvousChannel()
    case -1 => JChannel.newUnlimitedChannel()
    case _  => JChannel.newBufferedChannel(capacity)

  override def toString: String = delegate.toString

object Channel:
  def bufferedDefault[T]: Channel[T] = BufferCapacity.newChannel[T]
  def buffered[T](capacity: Int): Channel[T] = new Channel(capacity)
  def rendezvous[T]: Channel[T] = new Channel(0)
  def unlimited[T]: Channel[T] = new Channel(-1)
  def withCapacity[T](capacity: Int): Channel[T] = new Channel(capacity)
end Channel
