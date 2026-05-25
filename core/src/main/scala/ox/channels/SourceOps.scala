package ox.channels

import ox.*

import java.util

trait SourceOps[+T]:
  outer: Source[T] =>

  // run ops (eager)

  /*
  Implementation note: why we catch Throwable (a) at all, (b) why not NonFatal?
  As far (a), sea ADR #3.
  As for (b), in case there's an InterruptedException, we do catch it and propagate, but we also stop the fork.
  Propagating is needed as there might be downstream consumers, which are outside the scope, and would be otherwise
  unaware that producing from the source stopped. We also don't rethrow, as the scope is already winding down (as we're
  in a supervised scope, there's no other possibility to interrupt the fork).
   */

  /** Applies the given mapping function `f` to each value received from this source, and sends the results to the returned channel.
    *
    * Errors from this channel are propagated to the returned channel. Any exceptions that occur when invoking `f` are propagated as errors
    * to the returned channel as well.
    *
    * Must be run within a scope, as a child fork is created, which receives from this source and sends the mapped values to the resulting
    * one.
    *
    * @param f
    *   The mapping function.
    * @return
    *   A source, onto which results of the mapping function will be sent.
    */
  def map[U](f: T => U)(using Ox, BufferCapacity): Source[U] =
    val c2 = BufferCapacity.newChannel[U]
    forkPropagate(c2) {
      repeatWhile {
        receiveOrClosed() match
          case ChannelClosed.Done     => c2.doneOrClosed().discard; false
          case ChannelClosed.Error(r) => c2.errorOrClosed(r).discard; false
          case t: T @unchecked        => c2.send(f(t)); true
      }
    }.discard
    c2
  end map

  /** Applies the given consumer function `f` to each value received from this source.
    *
    * Errors from this channel are propagated to the returned channel. Any exceptions that occur when invoking `f` are propagated as errors
    * to the returned channel as well.
    *
    * Must be run within a scope, as a child fork is created, which receives from this source and sends the mapped values to the resulting
    * one.
    *
    * Useful for side-effects without result values, like logging and debugging.
    *
    * @param f
    *   The consumer function.
    * @return
    *   A source, which the values from the input source are passed to.
    */
  def tap(f: T => Unit)(using Ox, BufferCapacity): Source[T] = map(t =>
    f(t); t
  )

  /** Applies the given mapping function `f` to each value received from this source, for which the function is defined, and sends the
    * results to the returned channel. If `f` is not defined at a value, the value will be skipped.
    *
    * Errors from this channel are propagated to the returned channel. Any exceptions that occur when invoking `f` are propagated as errors
    * to the returned channel as well.
    *
    * Must be run within a scope, as a child fork is created, which receives from this source and sends the mapped values to the resulting
    * one.
    *
    * @param f
    *   The mapping function.
    * @return
    *   A source, onto which results of the mapping function will be sent.
    */
  def collect[U](f: PartialFunction[T, U])(using Ox, BufferCapacity): Source[U] =
    val c2 = BufferCapacity.newChannel[U]
    forkPropagate(c2) {
      repeatWhile {
        receiveOrClosed() match
          case ChannelClosed.Done                  => c2.doneOrClosed().discard; false
          case ChannelClosed.Error(r)              => c2.errorOrClosed(r).discard; false
          case t: T @unchecked if f.isDefinedAt(t) => c2.send(f(t)); true
          case _                                   => true // f is not defined at t, skipping
      }
    }.discard
    c2
  end collect

  def filter(f: T => Boolean)(using Ox, BufferCapacity): Source[T] = transform(_.filter(f))

  def transform[U](f: Iterator[T] => Iterator[U])(using Ox, BufferCapacity): Source[U] =
    val it = new Iterator[T]:
      private var v: Option[T | ChannelClosed] = None
      private def forceNext(): T | ChannelClosed = v match
        case None =>
          val temp = receiveOrClosed()
          v = Some(temp)
          temp
        case Some(t) => t
      override def hasNext: Boolean = forceNext() match
        case ChannelClosed.Done => false
        case _                  => true
      override def next(): T = forceNext() match
        case ChannelClosed.Done     => throw new NoSuchElementException
        case e: ChannelClosed.Error => throw e.toThrowable
        case t: T @unchecked        =>
          v = None
          t
    Source.fromIterator(f(it))
  end transform

end SourceOps
