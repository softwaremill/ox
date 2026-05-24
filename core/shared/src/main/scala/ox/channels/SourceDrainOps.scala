package ox.channels

import ox.channels.ChannelClosedUnion.mapUnlessError
import ox.channels.ChannelClosedUnion.orThrow
import ox.discard
import ox.repeatWhile

trait SourceDrainOps[+T]:
  outer: Source[T] =>

  /** Invokes the given function for each received value. Blocks until the channel is done.
    *
    * @throws ChannelClosedException.Error
    *   When there is an upstream error.
    */
  def foreach(f: T => Unit): Unit = foreachOrError(f).orThrow

  /** The "safe" variant of [[foreach]]. */
  def foreachOrError(f: T => Unit): Unit | ChannelClosed.Error =
    var result: Unit | ChannelClosed.Error = ()
    repeatWhile {
      receiveOrClosed() match
        case ChannelClosed.Done     => false
        case e: ChannelClosed.Error => result = e; false
        case t: T @unchecked        => f(t); true
    }
    result
  end foreachOrError

  /** Accumulates all values received from the channel into a list. Blocks until the channel is done.
    *
    * @throws ChannelClosedException.Error
    *   When there is an upstream error.
    */
  def toList: List[T] = toListOrError.orThrow

  /** The "safe" variant of [[toList]]. */
  def toListOrError: List[T] | ChannelClosed.Error =
    val b = List.newBuilder[T]
    foreachOrError(b += _).mapUnlessError(_ => b.result())

  /** Passes each received values from this channel to the given sink. Blocks until the channel is done.
    *
    * Errors are always propagated. Successful channel completion is propagated when `propagateDone` is set to `true`.
    */
  def pipeTo(sink: Sink[T], propagateDone: Boolean): Unit =
    repeatWhile {
      receiveOrClosed() match
        case ChannelClosed.Done     => if propagateDone then sink.doneOrClosed().discard; false
        case e: ChannelClosed.Error => sink.errorOrClosed(e.reason).discard; false
        case t: T @unchecked        => sink.send(t); true
    }

  /** Receives all values from the channel. Blocks until the channel is done.
    *
    * @throws ChannelClosedException.Error
    *   when there is an upstream error.
    */
  def drain(): Unit = drainOrError().orThrow

  /** The "safe" variant of [[drain]]. */
  def drainOrError(): Unit | ChannelClosed.Error = foreachOrError(_ => ())
end SourceDrainOps
