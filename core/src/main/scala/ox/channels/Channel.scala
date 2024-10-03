package ox.channels

import com.softwaremill.jox.{Channel as JChannel, Select as JSelect, SelectClause as JSelectClause, Sink as JSink, Source as JSource}

import ChannelClosedUnion.orThrow

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

//

/** A channel source, which can be used to receive values from the channel. See [[Channel]] for more details. */
trait Source[+T] extends SourceOps[T] with SourceDrainOps[T]:
  protected def delegate: JSource[Any] // we need to use `Any` as the java types are invariant (they use use-site variance)

  // Skipping variance checks here is fine, as the only way a `Received` instance is created is by this Source (Channel),
  // so no values of super-types of T which are not the original T will ever be provided
  /** Holds the result of a [[receiveClause]] that was selected during a call to [[select]]. */
  case class Received private[channels] (value: T @uncheckedVariance) extends SelectResult[T]

  /** The clause passed to [[select]], created using [[receiveClause]] or [[receiveOrDoneClause]]. */
  case class Receive private[channels] (delegate: JSelectClause[Any]) extends SelectClause[T]:
    type Result = Received

  /** Create a clause which can be used in [[select]]. The clause will receive a value from the current channel. */
  def receiveClause: Receive = Receive(delegate.receiveClause(t => Received(t.asInstanceOf[T])))

  /** Receive a value from the channel. For a variant which throws exceptions when the channel is closed, use [[receive]].
    *
    * @return
    *   Either a value of type `T`, or [[ChannelClosed]], when the channel is closed.
    */
  def receiveOrClosed(): T | ChannelClosed = ChannelClosed.fromJoxOrT(delegate.receiveOrClosed())

  /** Receive a value from the channel.
    *
    * @throws ChannelClosedException
    *   If the channel is in error.
    * @see
    *   [[receive]] and [[receiveOrClosed]].
    * @return
    *   Either a value of type `T`, or [[ChannelClosed.Done]], when the channel is done.
    */
  def receiveOrDone(): T | ChannelClosed.Done.type = receiveOrClosed() match
    case e: ChannelClosed.Error => throw e.toThrowable
    case ChannelClosed.Done     => ChannelClosed.Done
    case t: T @unchecked        => t

  /** Receive a value from the channel. For a variant which doesn't throw exceptions when the channel is closed, use [[receiveOrClosed()]].
    *
    * @throws ChannelClosedException
    *   If the channel is closed (done or in error).
    * @return
    *   Either a value of type `T`, or [[ChannelClosed]], when the channel is closed.
    */
  def receive(): T = receiveOrClosed().orThrow

  /** @return
    *   `true` if no more values can be received from this channel; [[Source.receive()]] will throw [[ChannelClosedException]]. When closed
    *   for receive, sending values is also not possible, [[isClosedForSend]] will return `true`.
    * @return
    *   `false`, if more values **might** be received from the channel, when calling [[Source.receive()]]. However, it's not guaranteed that
    *   some values will be available. They might be received concurrently, or filtered out if the channel is created using
    *   [[Source.mapAsView()]], [[Source.filterAsView()]] or [[Source.collectAsView()]].
    */
  def isClosedForReceive: Boolean = delegate.isClosedForReceive

  /** @return
    *   `Some` if no more values can be received from this channel; [[Source.receive()]] will throw [[ChannelClosedException]]. When closed
    *   for receive, sending values is also not possible, [[isClosedForSend]] will return `true`.
    */
  def isClosedForReceiveDetail: Option[ChannelClosed] = Option(ChannelClosed.fromJoxOrT(delegate.closedForReceive()))
end Source

/** Various operations which allow creating [[Source]] instances.
  *
  * Some need to be run within a concurrency scope, such as [[supervised]].
  */
object Source extends SourceCompanionOps

//

/** A channel sink, which can be used to send values to the channel. See [[Channel]] for more details. */
trait Sink[-T]:
  protected def delegate: JSink[Any] // we need to use `Any` as the java types are invariant (they use use-site variance)

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

  /** Send a value to the channel. For a variant which throws exceptions when the channel is closed, use [[send]].
    *
    * @param t
    *   The value to send. Not `null`.
    * @return
    *   Either `()`, or [[ChannelClosed]], when the channel is closed.
    */
  def sendOrClosed(t: T): Unit | ChannelClosed =
    val r = ChannelClosed.fromJoxOrUnit(delegate.asInstanceOf[JSink[T]].sendOrClosed(t))
    if r == null then () else r

  /** Send a value to the channel. For a variant which doesn't throw exceptions when the channel is closed, use [[sendOrClosed()]].
    *
    * @throws ChannelClosedException
    *   If the channel is closed (done or in error).
    * @param t
    *   The value to send. Not `null`.
    */
  def send(t: T): Unit = sendOrClosed(t).orThrow

  /** Close the channel, indicating an error.
    *
    * Any elements that are already buffered won't be delivered. Any send or receive operations that are in progress will complete with a
    * channel closed result.
    *
    * Subsequent [[sendOrClosed()]] and [[Source.receiveOrClosed()]] operations will return [[ChannelClosed]].
    *
    * For a variant which throws exceptions when the channel is closed, use [[error]].
    *
    * @param reason
    *   The reason of the error.
    * @return
    *   Either `()`, or [[ChannelClosed]], when the channel is already closed.
    */
  def errorOrClosed(reason: Throwable): Unit | ChannelClosed = ChannelClosed.fromJoxOrUnit(delegate.errorOrClosed(reason))

  /** Close the channel, indicating an error.
    *
    * Any elements that are already buffered won't be delivered. Any send or receive operations that are in progress will complete with a
    * channel closed result.
    *
    * Subsequent [[send()]] and [[Source.receive()]] operations will throw [[ChannelClosedException]].
    *
    * For a variant which doesn't throw exceptions when the channel is closed, use [[errorOrClosed]].
    *
    * @throws ChannelClosedException
    *   If the channel is already closed.
    * @param reason
    *   The reason of the error.
    */
  def error(reason: Throwable): Unit = errorOrClosed(reason).orThrow

  /** Close the channel, indicating that no more elements will be sent. Doesn't throw exceptions when the channel is closed, but returns a
    * value.
    *
    * Any elements that are already buffered will be delivered. Any send operations that are in progress will complete normally, when a
    * receiver arrives. Any pending receive operations will complete with a channel closed result.
    *
    * Subsequent [[sendOrClosed()]] operations will return [[ChannelClosed]].
    *
    * For a variant which throws exceptions when the channel is closed, use [[done]].
    *
    * @return
    *   Either `()`, or [[ChannelClosed]], when the channel is already closed.
    */
  def doneOrClosed(): Unit | ChannelClosed = ChannelClosed.fromJoxOrUnit(delegate.doneOrClosed())

  /** Close the channel, indicating that no more elements will be sent. Doesn't throw exceptions when the channel is closed, but returns a
    * value.
    *
    * Any elements that are already buffered will be delivered. Any send operations that are in progress will complete normally, when a
    * receiver arrives. Any pending receive operations will complete with a channel closed result.
    *
    * Subsequent [[send()]] operations will throw [[ChannelClosedException]].
    *
    * For a variant which doesn't throw exceptions when the channel is closed, use [[doneOrClosed]].
    *
    * @throws ChannelClosedException
    *   If the channel is already closed.
    */
  def done(): Unit = doneOrClosed().orThrow

  /** @return
    *   `true` if no more values can be sent to this channel; [[Sink.sendOrClosed()]] will return [[ChannelClosed]]. When closed for send,
    *   receiving using [[Source.receive()]] might still be possible, if the channel is done, and not in an error. This can be verified
    *   using [[isClosedForReceive]].
    */
  def isClosedForSend: Boolean = delegate.isClosedForSend

  /** @return
    *   `Some` if no more values can be sent to this channel; [[Sink.sendOrClosed()]] will return [[ChannelClosed]]. When closed for send,
    *   receiving using [[Source.receive()]] might still be possible, if the channel is done, and not in an error. This can be verified
    *   using [[isClosedForReceive]].
    */
  def isClosedForSendDetail: Option[ChannelClosed] = Option(ChannelClosed.fromJoxOrT(delegate.closedForSend()))
end Sink

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
  * Channels can be created using the channel's companion object. A rendezvous channel is created using [[Channel.rendezvous]]. A buffered
  * channel can be created either with a given capacity - by providing a positive integer to the [[Channel.buffered]] method - or with the
  * default capacity ([[BufferCapacity.default]]) using [[Channel.bufferedDefault]] . A rendezvous channel behaves like a buffered channel
  * with buffer size 0. An unlimited channel can be created using [[Channel.unlimited]].
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
  * In case the channel is closed, a [[ChannelClosedException]] is thrown. Alternatively, you can use the `orClosed` method variants (e.g.
  * [[sendOrClosed]], [[receiveOrClosed]]), which don't throw exceptions, but return a union type which includes one of [[ChannelClosed]]
  * values. Such a union type can be further converted to an exception, [[Either]] or [[Try]] using one of the extension methods in
  * [[ChannelClosedUnion]].
  *
  * @tparam T
  *   The type of the values processed by the channel.
  */
class Channel[T] private (capacity: Int) extends Source[T] with Sink[T]:
  protected override val delegate: JChannel[Any] = new JChannel(capacity)
  override def toString: String = delegate.toString

object Channel:
  /** Creates a buffered channel with the default capacity (16). */
  def bufferedDefault[T]: Channel[T] = BufferCapacity.newChannel[T]

  /** Creates a buffered channel with the given capacity. */
  def buffered[T](capacity: Int): Channel[T] = new Channel(capacity)

  /** Creates a rendezvous channel (without a buffer, senders & receivers must meet to exchange values). */
  def rendezvous[T]: Channel[T] = new Channel(0)

  /** Creates an unlimited channel (which can buffer an arbitrary number of values). */
  def unlimited[T]: Channel[T] = new Channel(-1)

  /** Creates a channel with the given capacity; -1 creates an [[unlimited]] channel, 0 creates a [[rendezvous]], positive values create a
    * [[buffered]] channel.
    */
  def withCapacity[T](capacity: Int): Channel[T] = new Channel(capacity)
end Channel
