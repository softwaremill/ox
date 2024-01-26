package ox.channels

import com.softwaremill.jox.{
  Channel as JChannel,
  CloseableChannel as JCloseableChannel,
  Select as JSelect,
  SelectClause as JSelectClause,
  Sink as JSink,
  Source as JSource
}

import scala.annotation.unchecked.uncheckedVariance

// select result: needs to be defined here, as implementations are defined here as well

/** Results of a [[select]] call, when clauses are passed (instead of a number of [[Source]]s). Each result corresponds to a clause, and can
  * be pattern-matched (using a path-dependent type)) to inspect which clause was selected.
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

// extensions

extension [T](v: T | ChannelClosed)
  inline def map[U](f: T => U): U | ChannelClosed = v match
    case ChannelClosed.Done     => ChannelClosed.Done
    case e: ChannelClosed.Error => e
    case t: T @unchecked        => f(t)

  /** Throw a [[ChannelClosedException]] if the provided value represents a closed channel (one of [[ChannelClosed]] values). */
  inline def orThrow: T = v match
    case c: ChannelClosed => throw c.toThrowable
    case t: T @unchecked  => t

  inline def isValue: Boolean = v match
    case _: ChannelClosed => false
    case _: T @unchecked  => true

//

/** Allows querying the channel for its closed status.
  *
  * A channel can be closed in two ways:
  *
  *   - using [[Sink.done]], indicating that no more elements will be sent
  *   - using [[Sink.error]], indicating an error
  */
trait ChannelState:
  protected def delegate: JCloseableChannel

  /** @return `true` if the channel is closed using [[Sink.done()]] or [[Sink.error()]]. */
  def isClosed: Boolean = delegate.isClosed

  /** @return `true` if the channel is closed using [[Sink.done()]]. `false` if it's not closed, or closed with an error. */
  def isDone: Boolean = delegate.isDone

  /** @return `true` if the channel is closed using [[Sink.error()]]. `false` if it's not closed, or is done. */
  def isError: Boolean = delegate.isError != null

  /** @return
    *   `Some`, with details on why the channel is closed (using [[Sink.done()]] or [[Sink.error()]]), or `None` if the channel is not
    *   closed.
    */
  def isClosedDetail: Option[ChannelClosed] =
    if delegate.isDone then Some(ChannelClosed.Done)
    else isErrorDetail

  /** @return
    *   `Some`, with details on the channel's error (provided using [[Sink.error()]]), or `None` if the channel is not closed or is done.
    */
  def isErrorDetail: Option[ChannelClosed.Error] =
    delegate.isError match
      case null => None
      case t    => Some(ChannelClosed.Error(t))

/** A channel source, which can be used to receive values from the channel. See [[Channel]] for more details. */
trait Source[+T] extends SourceOps[T] with ChannelState:
  protected override def delegate: JSource[Any] // we need to use `Any` as the java types are invariant (they use use-site variance)

  // Skipping variance checks here is fine, as the only way a `Received` instance is created is by this Source (Channel),
  // so no values of super-types of T which are not the original T will ever be provided
  /** Holds the result of a [[receiveClause]] that was selected during a call to [[select]]. */
  case class Received private[channels] (value: T @uncheckedVariance) extends SelectResult[T]

  /** The clause passed to [[select]], created using [[receiveClause]] or [[receiveOrDoneClause]]. */
  case class Receive private[channels] (delegate: JSelectClause[Any]) extends SelectClause[T]:
    type Result = Received

  /** Create a clause which can be used in [[select]]. The clause will receive a value from the current channel.
    *
    * If the source is/becomes done, [[select]] will restart with channels that are not done yet.
    */
  def receiveClause: Receive = Receive(delegate.receiveClause(t => Received(t.asInstanceOf[T])))

  /** Create a clause which can be used in [[select]]. The clause will receive a value from the current channel.
    *
    * If the source is/becomes done, [[select]] will stop and return a [[ChannelClosed.Done]] value.
    */
  def receiveOrDoneClause: Receive = Receive(delegate.receiveOrDoneClause(t => Received(t.asInstanceOf[T])))

  /** Receive a value from the channel. To throw an exception when the channel is closed, use [[orThrow]].
    *
    * @return
    *   Either a value of type `T`, or [[ChannelClosed]], when the channel is closed.
    */
  def receive(): T | ChannelClosed = ChannelClosed.fromJoxOrT(delegate.receiveSafe())

/** Various operations which allow creating [[Source]] instances.
  *
  * Some need to be run within a concurrency scope, such as [[supervised]].
  */
object Source extends SourceCompanionOps

//

/** A channel sink, which can be used to send values to the channel. See [[Channel]] for more details. */
trait Sink[-T] extends ChannelState:
  protected override def delegate: JSink[Any] // we need to use `Any` as the java types are invariant (they use use-site variance)

  /** Holds the result of a [[sendClause]] that was selected during a call to [[select]]. */
  case class Sent private[channels] () extends SelectResult[Unit]:
    override def value: Unit = ()

  /** The clause passed to [[select]], created using [[sendClause]]. */
  case class Send private[channels] (delegate: JSelectClause[Any]) extends SelectClause[Unit]:
    type Result = Sent

  /** Create a clause which can be used in [[select]]. The clause will send the given value to the current channel, and return `()` as the
    * clause's result.
    */
  def sendClause(t: T): Send = Send(delegate.asInstanceOf[JSink[T]].sendClause(t, () => Sent()))

  /** Send a value to the channel. To throw an exception when the channel is closed, use [[orThrow]].
    *
    * @param t
    *   The value to send. Not `null`.
    * @return
    *   Either `()`, or [[ChannelClosed]], when the channel is closed.
    */
  def send(t: T): Unit | ChannelClosed =
    val r = ChannelClosed.fromJoxOrUnit(delegate.asInstanceOf[JSink[T]].sendSafe(t))
    if r == null then () else r

  /** Close the channel, indicating an error.
    *
    * Any elements that are already buffered won't be delivered. Any send or receive operations that are in progress will complete with a
    * channel closed result.
    *
    * Subsequent [[send()]] and [[Source.receive()]] operations will return [[ChannelClosed]]..
    *
    * @param reason
    *   The reason of the error.
    *
    * @return
    *   Either `()`, or [[ChannelClosed]], when the channel is already closed.
    */
  def error(reason: Throwable): Unit | ChannelClosed = ChannelClosed.fromJoxOrUnit(delegate.errorSafe(reason))

  /** Close the channel, indicating that no more elements will be sent. Doesn't throw exceptions when the channel is closed, but returns a
    * value.
    *
    * Any elements that are already buffered will be delivered. Any send operations that are in progress will complete normally, when a
    * receiver arrives. Any pending receive operations will complete with a channel closed result.
    *
    * Subsequent [[send()]] operations will return [[ChannelClosed]].
    *
    * @return
    *   Either `()`, or [[ChannelClosed]], when the channel is already closed.
    */
  def done(): Unit | ChannelClosed = ChannelClosed.fromJoxOrUnit(delegate.doneSafe())

//

/** Channel is a thread-safe data structure which exposes three basic operations:
  *
  *   - [[send]]-ing a value to the channel. Values can't be `null`.
  *   - [[receive]]-ing a value from the channel
  *   - closing the channel using [[done]] or [[error]]
  *
  * There are three channel flavors:
  *
  *   - rendezvous channels, where senders and receivers must meet to exchange values
  *   - buffered channels, where a given number of sent values might be buffered, before subsequent `send`s block
  *   - unlimited channels, where an unlimited number of values might be buffered, hence `send` never blocks
  *
  * Channels can be created using the channel's companion object. When no arguments are given, a rendezvous channel is created, while a
  * buffered channel can be created by providing a positive integer to the [[Channel.apply]] method. A rendezvous channel behaves like a
  * buffered channel with buffer size 0. An unlimited channel can be created using [[Channel.unlimited]].
  *
  * In a rendezvous channel, senders and receivers block, until a matching party arrives (unless one is already waiting). Similarly,
  * buffered channels block if the buffer is full (in case of senders), or in case of receivers, if the buffer is empty and there are no
  * waiting senders.
  *
  * All blocking operations behave properly upon interruption.
  *
  * Channels might be closed, either because no more values will be produced by the source (using [[done]]), or because there was an error
  * while producing or processing the received values (using [[error]]).
  *
  * After closing, no more values can be sent to the channel. If the channel is "done", any pending sends will be completed normally. If the
  * channel is in an "error" state, pending sends will be interrupted and will return with the reason for the closure.
  *
  * In case the channel is closed, one of the [[ChannelClosed]] values are returned. These can be converted to an exception by calling
  * [[orThrow]] on a result which includes [[ChannelClosed]] as one of the components of the union type.
  *
  * @tparam T
  *   The type of the values processed by the channel.
  */
class Channel[T](capacity: Int) extends Source[T] with Sink[T]:
  protected override val delegate: JChannel[Any] = new JChannel(capacity)
  override def toString: String = delegate.toString

object Channel:
  /** Creates a buffered channel (when capacity is positive), or a rendezvous channel if the capacity is 0. */
  def apply[T](capacity: Int = 0): Channel[T] = new Channel(capacity)

  /** Creates an unlimited channel (which can buffer an arbitrary number of elements). */
  def unlimited[T]: Channel[T] = new Channel(-1)
