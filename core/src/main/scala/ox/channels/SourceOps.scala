package ox.channels

import com.softwaremill.jox.Source as JSource
import ox.*

import java.util

trait SourceOps[+T]:
  outer: Source[T] =>
  // view ops (lazy)

  /** Lazily-evaluated map: creates a view of this source, where the results of [[receive]] will be transformed on the consumer's thread
    * using the given function `f`. For an eager, asynchronous version, see [[map]].
    *
    * The same logic applies to receive clauses created using this source, which can be used in [[select]].
    *
    * @param f
    *   The mapping function. Results should not be `null`.
    * @return
    *   A source which is a view of this source, with the mapping function applied.
    */
  def mapAsView[U](f: T => U): Source[U] = new Source[U]:
    override val delegate: JSource[Any] = outer.delegate.asInstanceOf[JSource[T]].collectAsView(t => f(t))

  /** Lazily-evaluated tap: creates a view of this source, where the results of [[receive]] will be applied to the given function `f` on the
    * consumer's thread. Useful for side-effects without result values, like logging and debugging. For an eager, asynchronous version, see
    * [[tap]].
    *
    * The same logic applies to receive clauses created using this source, which can be used in [[select]].
    *
    * @param f
    *   The consumer function.
    * @return
    *   A source which is a view of this source, with the consumer function applied.
    */
  def tapAsView(f: T => Unit): Source[T] = mapAsView(t =>
    f(t); t
  )

  /** Lazily-evaluated filter: Creates a view of this source, where the results of [[receive]] will be filtered on the consumer's thread
    * using the given predicate `p`. For an eager, asynchronous version, see [[filter]].
    *
    * The same logic applies to receive clauses created using this source, which can be used in [[select]].
    *
    * @param f
    *   The predicate to use for filtering.
    * @return
    *   A source which is a view of this source, with the filtering function applied.
    */
  def filterAsView(f: T => Boolean): Source[T] = new Source[T]:
    override val delegate: JSource[Any] = outer.delegate.filterAsView(t => f(t.asInstanceOf[T]))

  /** Creates a view of this source, where the results of [[receive]] will be transformed on the consumer's thread using the given function
    * `f`. If the function is not defined at an element, the element will be skipped. For an eager, asynchronous version, see [[collect]].
    *
    * The same logic applies to receive clauses created using this source, which can be used in [[select]].
    *
    * @param f
    *   The collecting function. Results should not be `null`.
    * @return
    *   A source which is a view of this source, with the collecting function applied.
    */
  def collectAsView[U](f: PartialFunction[T, U]): Source[U] = new Source[U]:
    override val delegate: JSource[Any] = outer.delegate.collectAsView(t => f.applyOrElse(t.asInstanceOf[T], _ => null))

  // run ops (eager)

  /*
  Implementation note: why we catch Throwable (a) at all, (b) why not NonFatal?
  As far (a), sea ADR #3.
  As for (b), in case there's an InterruptedException, we do catch it and propagate, but we also stop the fork.
  Propagating is needed as there might be downstream consumers, which are outside the scope, and would be otherwise
  unaware that producing from the source stopped. We also don't rethrow, as the scope is already winding down (as we're
  in a supervised scope, there's no other possibility to interrupt the fork).
   */

  /** Applies the given mapping function `f` to each element received from this source, and sends the results to the returned channel.
    *
    * Errors from this channel are propagated to the returned channel. Any exceptions that occur when invoking `f` are propagated as errors
    * to the returned channel as well.
    *
    * Must be run within a scope, as a child fork is created, which receives from this source and sends the mapped values to the resulting
    * one.
    *
    * For a lazily-evaluated version, see [[mapAsView]].
    *
    * @param f
    *   The mapping function.
    * @return
    *   A source, onto which results of the mapping function will be sent.
    */
  def map[U](f: T => U)(using Ox, StageCapacity): Source[U] =
    val c2 = StageCapacity.newChannel[U]
    forkPropagate(c2) {
      repeatWhile {
        receiveOrClosed() match
          case ChannelClosed.Done     => c2.doneOrClosed(); false
          case ChannelClosed.Error(r) => c2.errorOrClosed(r); false
          case t: T @unchecked        => c2.send(f(t)); true
      }
    }
    c2
  end map

  /** Applies the given consumer function `f` to each element received from this source.
    *
    * Errors from this channel are propagated to the returned channel. Any exceptions that occur when invoking `f` are propagated as errors
    * to the returned channel as well.
    *
    * Must be run within a scope, as a child fork is created, which receives from this source and sends the mapped values to the resulting
    * one.
    *
    * Useful for side-effects without result values, like logging and debugging. For a lazily-evaluated version, see [[tapAsView]].
    *
    * @param f
    *   The consumer function.
    * @return
    *   A source, which the elements from the input source are passed to.
    */
  def tap(f: T => Unit)(using Ox, StageCapacity): Source[T] = map(t =>
    f(t); t
  )

  /** Applies the given mapping function `f` to each element received from this source, for which the function is defined, and sends the
    * results to the returned channel. If `f` is not defined at an element, the element will be skipped.
    *
    * Errors from this channel are propagated to the returned channel. Any exceptions that occur when invoking `f` are propagated as errors
    * to the returned channel as well.
    *
    * Must be run within a scope, as a child fork is created, which receives from this source and sends the mapped values to the resulting
    * one.
    *
    * For a lazily-evaluated version, see [[collectAsView]].
    *
    * @param f
    *   The mapping function.
    * @return
    *   A source, onto which results of the mapping function will be sent.
    */
  def collect[U](f: PartialFunction[T, U])(using Ox, StageCapacity): Source[U] =
    val c2 = StageCapacity.newChannel[U]
    forkPropagate(c2) {
      repeatWhile {
        receiveOrClosed() match
          case ChannelClosed.Done                  => c2.doneOrClosed(); false
          case ChannelClosed.Error(r)              => c2.errorOrClosed(r); false
          case t: T @unchecked if f.isDefinedAt(t) => c2.send(f(t)); true
          case _                                   => true // f is not defined at t, skipping
      }
    }
    c2
  end collect

  def filter(f: T => Boolean)(using Ox, StageCapacity): Source[T] = transform(_.filter(f))

  def transform[U](f: Iterator[T] => Iterator[U])(using Ox, StageCapacity): Source[U] =
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
        case t: T @unchecked =>
          v = None
          t
    Source.fromIterator(f(it))
  end transform

end SourceOps
