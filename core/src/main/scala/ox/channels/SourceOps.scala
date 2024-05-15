package ox.channels

import com.softwaremill.jox.Source as JSource
import ox.*
import ox.channels.ChannelClosedUnion.isValue

import java.util
import java.util.concurrent.{CountDownLatch, Semaphore}
import scala.collection.IterableOnce
import scala.concurrent.duration.*

trait SourceOps[+T] { outer: Source[T] =>
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
  def mapAsView[U](f: T => U): Source[U] = new Source[U] {
    override val delegate: JSource[Any] = outer.delegate.asInstanceOf[JSource[T]].collectAsView(t => f(t))
  }

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
  def filterAsView(f: T => Boolean): Source[T] = new Source[T] {
    override val delegate: JSource[Any] = outer.delegate.filterAsView(t => f(t.asInstanceOf[T]))
  }

  /** Creates a view of this source, where the results of [[receive]] will be transformed on the consumer's thread using the given function
    * `f`. If the function is not defined at an element, the element will be skipped.
    *
    * The same logic applies to receive clauses created using this source, which can be used in [[select]].
    *
    * @param f
    *   The collecting function. Results should not be `null`.
    * @return
    *   A source which is a view of this source, with the collecting function applied.
    */
  def collectAsView[U](f: PartialFunction[T, U]): Source[U] = new Source[U] {
    override val delegate: JSource[Any] = outer.delegate.collectAsView(t => f.applyOrElse(t.asInstanceOf[T], _ => null))
  }

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
    * to the returned channel as wel.
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
    fork {
      repeatWhile {
        receiveOrClosed() match
          case ChannelClosed.Done     => c2.doneOrClosed(); false
          case ChannelClosed.Error(r) => c2.errorOrClosed(r); false
          case t: T @unchecked =>
            try
              val u = f(t)
              c2.sendOrClosed(u).isValue
            catch
              case t: Throwable =>
                c2.errorOrClosed(t)
                false
      }
    }
    c2

  /** Intersperses this source with provided element and forwards it to the returned channel.
    *
    * @param inject
    *   An element to be injected between the stream elements.
    * @return
    *   A source, onto which elements will be injected.
    * @example
    *   {{{
    *   import ox.*
    *   import ox.channels.Source
    *
    *   supervised {
    *     Source.empty[String].intersperse(", ").toList            // List()
    *     Source.fromValues("foo").intersperse(", ").toList        // List(foo)
    *     Source.fromValues("foo", "bar").intersperse(", ").toList // List(foo, ", ", bar)
    *   }
    *   }}}
    */
  def intersperse[U >: T](inject: U)(using Ox, StageCapacity): Source[U] =
    intersperse(None, inject, None)

  /** Intersperses this source with start, end and provided elements and forwards it to the returned channel.
    *
    * @param start
    *   An element to be prepended to the stream.
    * @param inject
    *   An element to be injected between the stream elements.
    * @param end
    *   An element to be appended to the end of the stream.
    * @return
    *   A source, onto which elements will be injected.
    * @example
    *   {{{
    *   import ox.*
    *   import ox.channels.Source
    *
    *   supervised {
    *     Source.empty[String].intersperse("[", ", ", "]").toList            // List([, ])
    *     Source.fromValues("foo").intersperse("[", ", ", "]").toList        // List([, foo, ])
    *     Source.fromValues("foo", "bar").intersperse("[", ", ", "]").toList // List([, foo, ", ", bar, ])
    *   }
    *   }}}
    */
  def intersperse[U >: T](start: U, inject: U, end: U)(using Ox, StageCapacity): Source[U] =
    intersperse(Some(start), inject, Some(end))

  private def intersperse[U >: T](start: Option[U], inject: U, end: Option[U])(using Ox, StageCapacity): Source[U] =
    val c = StageCapacity.newChannel[U]
    fork {
      start.foreach(c.sendOrClosed)
      var firstEmitted = false
      repeatWhile {
        receiveOrClosed() match
          case ChannelClosed.Done               => end.foreach(c.sendOrClosed); c.doneOrClosed(); false
          case ChannelClosed.Error(e)           => c.errorOrClosed(e); false
          case v: U @unchecked if !firstEmitted => firstEmitted = true; c.sendOrClosed(v); true
          case v: U @unchecked                  => c.sendOrClosed(inject); c.sendOrClosed(v); true
      }
    }
    c

  /** Applies the given mapping function `f` to each element received from this source, and sends the results to the returned channel. At
    * most `parallelism` invocations of `f` are run in parallel.
    *
    * The mapped results are sent to the returned channel in the same order, in which inputs are received from this source. In other words,
    * ordering is preserved.
    *
    * Errors from this channel are propagated to the returned channel. Any exceptions that occur when invoking `f` are propagated as errors
    * to the returned channel as well, and result in interrupting any mappings that are in progress.
    *
    * Must be run within a scope, as child forks are created, which receive from this source, send to the resulting one, and run the
    * mappings.
    *
    * @param parallelism
    *   An upper bound on the number of forks that run in parallel. Each fork runs the function `f` on a single element of the source.
    * @param f
    *   The mapping function.
    * @return
    *   A source, onto which results of the mapping function will be sent.
    */
  def mapPar[U](parallelism: Int)(f: T => U)(using Ox, StageCapacity): Source[U] =
    val c2 = StageCapacity.newChannel[U]
    fork(mapParScope(parallelism, c2, f))
    c2

  private def mapParScope[U](parallelism: Int, c2: Channel[U], f: T => U): Unit =
    val s = new Semaphore(parallelism)
    val inProgress = Channel.withCapacity[Fork[Option[U]]](parallelism)
    val closeScope = new CountDownLatch(1)
    unsupervised {
      // enqueueing fork
      forkUnsupervised {
        repeatWhile {
          s.acquire()
          receiveOrClosed() match
            case ChannelClosed.Done =>
              inProgress.doneOrClosed()
              false
            case ChannelClosed.Error(r) =>
              c2.errorOrClosed(r)
              // closing the scope, any child forks will be cancelled before the scope is done
              closeScope.countDown()
              false
            case t: T @unchecked =>
              inProgress.sendOrClosed(forkUnsupervised {
                try
                  val u = f(t)
                  s.release() // not in finally, as in case of an exception, no point in starting subsequent forks
                  Some(u)
                catch
                  case t: Throwable =>
                    c2.errorOrClosed(t)
                    closeScope.countDown()
                    None
              })
              true
        }
      }

      // sending fork
      forkUnsupervised {
        repeatWhile {
          inProgress.receiveOrClosed() match
            case f: Fork[Option[U]] @unchecked =>
              f.join().map(c2.sendOrClosed).isDefined
            case ChannelClosed.Done =>
              closeScope.countDown()
              c2.doneOrClosed()
              false
            case ChannelClosed.Error(_) =>
              throw new IllegalStateException() // inProgress is never in an error state
        }
      }

      closeScope.await()
    }

  def mapParUnordered[U](parallelism: Int)(f: T => U)(using Ox, StageCapacity): Source[U] =
    val c = StageCapacity.newChannel[U]
    val s = new Semaphore(parallelism)
    fork {
      supervised {
        repeatWhile {
          s.acquire()
          receiveOrClosed() match
            case ChannelClosed.Done => false
            case ChannelClosed.Error(r) =>
              c.errorOrClosed(r)
              false
            case t: T @unchecked =>
              forkUser {
                try
                  c.sendOrClosed(f(t))
                  s.release()
                catch case t: Throwable => c.errorOrClosed(t)
              }
              true
        }
      }
      c.doneOrClosed()
    }
    c

  def take(n: Int)(using Ox, StageCapacity): Source[T] = transform(_.take(n))

  /** Sends elements to the returned channel until predicate `f` is satisfied (returns `true`). Note that when the predicate `f` is not
    * satisfied (returns `false`), subsequent elements are dropped even if they could still satisfy it.
    *
    * @param f
    *   A predicate function.
    * @example
    *   {{{
    *   import ox.*
    *   import ox.channels.Source
    *
    *   supervised {
    *     Source.empty[Int].takeWhile(_ > 3).toList          // List()
    *     Source.fromValues(1, 2, 3).takeWhile(_ < 3).toList // List(1, 2)
    *     Source.fromValues(3, 2, 1).takeWhile(_ < 3).toList // List()
    *   }
    *   }}}
    */
  def takeWhile(f: T => Boolean)(using Ox, StageCapacity): Source[T] = transform(_.takeWhile(f))

  /** Drops `n` elements from this source and forwards subsequent elements to the returned channel.
    *
    * @param n
    *   Number of elements to be dropped.
    * @example
    *   {{{
    *   import ox.*
    *   import ox.channels.Source
    *
    *   supervised {
    *     Source.empty[Int].drop(1).toList          // List()
    *     Source.fromValues(1, 2, 3).drop(1).toList // List(2 ,3)
    *     Source.fromValues(1).drop(2).toList       // List()
    *   }
    *   }}}
    */
  def drop(n: Int)(using Ox, StageCapacity): Source[T] = transform(_.drop(n))

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

  def merge[U >: T](other: Source[U])(using Ox, StageCapacity): Source[U] =
    val c = StageCapacity.newChannel[U]

    def drainFrom(toDrain: Source[U]): Unit =
      repeatWhile {
        toDrain.receiveOrClosed() match
          case ChannelClosed.Done     => c.doneOrClosed(); false
          case ChannelClosed.Error(r) => c.errorOrClosed(r); false
          case t: U @unchecked        => c.sendOrClosed(t).isValue
      }

    fork {
      repeatWhile {
        selectOrClosed(this, other) match
          case ChannelClosed.Done =>
            if this.isClosedForReceive then drainFrom(other) else drainFrom(this)
            false
          case ChannelClosed.Error(r) => c.errorOrClosed(r); false
          case r: U @unchecked        => c.sendOrClosed(r).isValue
      }
    }
    c

  def concat[U >: T](other: Source[U])(using Ox, StageCapacity): Source[U] =
    Source.concat(List(() => this, () => other))

  def zip[U](other: Source[U])(using Ox, StageCapacity): Source[(T, U)] =
    val c = StageCapacity.newChannel[(T, U)]
    fork {
      repeatWhile {
        receiveOrClosed() match
          case ChannelClosed.Done     => c.doneOrClosed(); false
          case ChannelClosed.Error(r) => c.errorOrClosed(r); false
          case t: T @unchecked =>
            other.receiveOrClosed() match
              case ChannelClosed.Done     => c.doneOrClosed(); false
              case ChannelClosed.Error(r) => c.errorOrClosed(r); false
              case u: U @unchecked        => c.sendOrClosed(t, u).isValue
      }
    }
    c

  /** Combines elements from this and other sources into tuples handling early completion of either source with defaults.
    *
    * @param other
    *   A source of elements to be combined with.
    * @param thisDefault
    *   A default element to be used in the result tuple when the other source is longer.
    * @param otherDefault
    *   A default element to be used in the result tuple when the current source is longer.
    * @example
    *   {{{
    *   import ox.*
    *   import ox.channels.Source
    *
    *   supervised {
    *     Source.empty[Int].zipAll(Source.empty[String], -1, "foo").toList      // List()
    *     Source.empty[Int].zipAll(Source.fromValues("a"), -1, "foo").toList    // List((-1, "a"))
    *     Source.fromValues(1).zipAll(Source.empty[String], -1, "foo").toList   // List((1, "foo"))
    *     Source.fromValues(1).zipAll(Source.fromValues("a"), -1, "foo").toList // List((1, "a"))
    *   }
    *   }}}
    */
  def zipAll[U >: T, V](other: Source[V], thisDefault: U, otherDefault: V)(using Ox, StageCapacity): Source[(U, V)] =
    val c = StageCapacity.newChannel[(U, V)]

    def receiveFromOther(thisElement: U, otherClosedHandler: () => Boolean): Boolean =
      other.receiveOrClosed() match
        case ChannelClosed.Done     => otherClosedHandler()
        case ChannelClosed.Error(r) => c.errorOrClosed(r); false
        case v: V @unchecked        => c.sendOrClosed(thisElement, v); true

    fork {
      repeatWhile {
        receiveOrClosed() match
          case ChannelClosed.Done     => receiveFromOther(thisDefault, () => { c.doneOrClosed(); false })
          case ChannelClosed.Error(r) => c.errorOrClosed(r); false
          case t: T @unchecked        => receiveFromOther(t, () => { c.sendOrClosed(t, otherDefault); true })
      }
    }
    c

  /** Sends a given number of elements (determined byc `segmentSize`) from this source to the returned channel, then sends the same number
    * of elements from the `other` source and repeats. The order of elements in both sources is preserved.
    *
    * If one of the sources is done before the other, the behavior depends on the `eagerCancel` flag. When set to `true`, the returned
    * channel is completed immediately, otherwise the remaining elements from the other source are sent to the returned channel.
    *
    * Must be run within a scope, since a child fork is created which receives from both sources and sends to the resulting channel.
    *
    * @param other
    *   The source whose elements will be interleaved with the elements of this source.
    * @param segmentSize
    *   The number of elements sent from each source before switching to the other one. Default is 1.
    * @param eagerComplete
    *   If `true`, the returned channel is completed as soon as either of the sources completes. If `false`, the remaining elements of the
    *   non-completed source are sent downstream.
    * @return
    *   A source to which the interleaved elements from both sources would be sent.
    * @example
    *   {{{
    *   scala>
    *   import ox.*
    *   import ox.channels.Source
    *
    *   supervised {
    *     val s1 = Source.fromValues(1, 2, 3, 4, 5, 6, 7)
    *     val s2 = Source.fromValues(10, 20, 30, 40)
    *     s1.interleave(s2, segmentSize = 2).toList
    *   }
    *
    *   scala> val res0: List[Int] = List(1, 2, 10, 20, 3, 4, 30, 40, 5, 6, 7)
    *   }}}
    */
  def interleave[U >: T](other: Source[U], segmentSize: Int = 1, eagerComplete: Boolean = false)(using Ox, StageCapacity): Source[U] =
    Source.interleaveAll(List(this, other), segmentSize, eagerComplete)

  /** Applies the given mapping function `f`, using additional state, to each element received from this source, and sends the results to
    * the returned channel. Optionally sends an additional element, possibly based on the final state, to the returned channel once this
    * source is done.
    *
    * The `initializeState` function is called once when `statefulMap` is called.
    *
    * The `onComplete` function is called once when this source is done. If it returns a non-empty value, the value will be sent to the
    * returned channel, while an empty value will be ignored.
    *
    * @param initializeState
    *   A function that initializes the state.
    * @param f
    *   A function that transforms the element from this source and the state into a pair of the next state and the result which is sent
    *   sent to the returned channel.
    * @param onComplete
    *   A function that transforms the final state into an optional element sent to the returned channel. By default the final state is
    *   ignored.
    * @return
    *   A source to which the results of applying `f` to the elements from this source would be sent.
    * @example
    *   {{{
    *   scala>
    *   import ox.*
    *   import ox.channels.Source
    *
    *   supervised {
    *     val s = Source.fromValues(1, 2, 3, 4, 5)
    *     s.mapStateful(() => 0)((sum, element) => (sum + element, sum), Some.apply)
    *   }
    *
    *   scala> val res0: List[Int] = List(0, 1, 3, 6, 10, 15)
    *   }}}
    */
  def mapStateful[S, U](
      initializeState: () => S
  )(f: (S, T) => (S, U), onComplete: S => Option[U] = (_: S) => None)(using Ox, StageCapacity): Source[U] =
    def resultToSome(s: S, t: T) =
      val (newState, result) = f(s, t)
      (newState, Some(result))

    mapStatefulConcat(initializeState)(resultToSome, onComplete)

  /** Applies the given mapping function `f`, using additional state, to each element received from this source, and sends the results one
    * by one to the returned channel. Optionally sends an additional element, possibly based on the final state, to the returned channel
    * once this source is done.
    *
    * The `initializeState` function is called once when `statefulMap` is called.
    *
    * The `onComplete` function is called once when this source is done. If it returns a non-empty value, the value will be sent to the
    * returned channel, while an empty value will be ignored.
    *
    * @param initializeState
    *   A function that initializes the state.
    * @param f
    *   A function that transforms the element from this source and the state into a pair of the next state and a
    *   [[scala.collection.IterableOnce]] of results which are sent one by one to the returned channel. If the result of `f` is empty,
    *   nothing is sent to the returned channel.
    * @param onComplete
    *   A function that transforms the final state into an optional element sent to the returned channel. By default the final state is
    *   ignored.
    * @return
    *   A source to which the results of applying `f` to the elements from this source would be sent.
    * @example
    *   {{{
    *   scala>
    *   import ox.*
    *   import ox.channels.Source
    *
    *   supervised {
    *     val s = Source.fromValues(1, 2, 2, 3, 2, 4, 3, 1, 5)
    *     // deduplicate the values
    *     s.mapStatefulConcat(() => Set.empty[Int])((s, e) => (s + e, Option.unless(s.contains(e))(e)))
    *   }
    *
    *   scala> val res0: List[Int] = List(1, 2, 3, 4, 5)
    *   }}}
    */
  def mapStatefulConcat[S, U](
      initializeState: () => S
  )(f: (S, T) => (S, IterableOnce[U]), onComplete: S => Option[U] = (_: S) => None)(using Ox, StageCapacity): Source[U] =
    val c = StageCapacity.newChannel[U]
    fork {
      var state = initializeState()
      repeatWhile {
        receiveOrClosed() match
          case ChannelClosed.Done =>
            try
              onComplete(state).foreach(c.sendOrClosed)
              c.doneOrClosed()
            catch case t: Throwable => c.errorOrClosed(t)
            false
          case ChannelClosed.Error(r) =>
            c.errorOrClosed(r)
            false
          case t: T @unchecked =>
            try
              val (nextState, result) = f(state, t)
              state = nextState
              result.iterator.map(c.sendOrClosed).forall(_.isValue)
            catch
              case t: Throwable =>
                c.errorOrClosed(t)
                false
      }
    }
    c

  /** Applies the given mapping function `f`, to each element received from this source, transforming it into an Iterable of results, then
    * sends the results one by one to the returned channel. Can be used to unfold incoming sequences of elements into single elements.
    *
    * @param f
    *   A function that transforms the element from this source into a pair of the next state into an [[scala.collection.IterableOnce]] of
    *   results which are sent one by one to the returned channel. If the result of `f` is empty, nothing is sent to the returned channel.
    * @return
    *   A source to which the results of applying `f` to the elements from this source would be sent.
    * @example
    *   {{{
    *   scala>
    *   import ox.*
    *   import ox.channels.Source
    *
    *   supervised {
    *     val s = Source.fromValues(List(1, 2, 3), List(4, 5, 6), List(7, 8, 9))
    *     s.mapConcat(identity)
    *   }
    *
    *   scala> val res0: List[Int] = List(1, 2, 3, 4, 5, 6, 7, 8, 9)
    *   }}}
    */
  def mapConcat[U](f: T => IterableOnce[U])(using Ox, StageCapacity): Source[U] =
    val c = StageCapacity.newChannel[U]
    fork {
      repeatWhile {
        receiveOrClosed() match
          case ChannelClosed.Done =>
            c.doneOrClosed()
            false
          case ChannelClosed.Error(r) =>
            c.errorOrClosed(r)
            false
          case t: T @unchecked =>
            try
              val results: IterableOnce[U] = f(t)
              results.iterator.foreach(c.send)
              true
            catch
              case t: Throwable =>
                c.errorOrClosed(t)
                false
      }
    }
    c

  /** Returns the first element from this source wrapped in [[Some]] or [[None]] when this source is empty. Note that `headOption` is not an
    * idempotent operation on source as it receives elements from it.
    *
    * @return
    *   A `Some(first element)` if source is not empty or `None` otherwise.
    * @throws ChannelClosedException.Error
    *   When receiving an element from this source fails.
    * @example
    *   {{{
    *   import ox.*
    *   import ox.channels.Source
    *
    *   supervised {
    *     Source.empty[Int].headOption()  // None
    *     val s = Source.fromValues(1, 2)
    *     s.headOption()                  // Some(1)
    *     s.headOption()                  // Some(2)
    *   }
    *   }}}
    */
  def headOption(): Option[T] =
    supervised {
      receiveOrClosed() match
        case ChannelClosed.Done     => None
        case e: ChannelClosed.Error => throw e.toThrowable
        case t: T @unchecked        => Some(t)
    }

  /** Sends elements to the returned channel limiting the throughput to specific number of elements (evenly spaced) per time unit. Note that
    * the element's `receive()` time is included in the resulting throughput. For instance having `throttle(1, 1.second)` and `receive()`
    * taking `Xms` means that resulting channel will receive elements every `1s + Xms` time. Throttling is not applied to the empty source.
    *
    * @param elements
    *   Number of elements to be emitted. Must be greater than 0.
    * @param per
    *   Per time unit. Must be greater or equal to 1 ms.
    * @return
    *   A source that emits at most `elements` `per` time unit.
    * @example
    *   {{{
    *   import ox.*
    *   import ox.channels.Source
    *
    *   import scala.concurrent.duration.*
    *
    *   supervised {
    *     Source.empty[Int].throttle(1, 1.second).toList       // List() returned without throttling
    *     Source.fromValues(1, 2).throttle(1, 1.second).toList // List(1, 2) returned after 2 seconds
    *   }
    *   }}}
    */
  def throttle(elements: Int, per: FiniteDuration)(using Ox, StageCapacity): Source[T] =
    require(elements > 0, "elements must be > 0")
    require(per.toMillis > 0, "per time must be >= 1 ms")

    val c = StageCapacity.newChannel[T]
    val emitEveryMillis = (per.toMillis / elements).millis

    fork {
      repeatWhile {
        receiveOrClosed() match
          case ChannelClosed.Done     => c.doneOrClosed(); false
          case ChannelClosed.Error(r) => c.errorOrClosed(r); false
          case t: T @unchecked        => sleep(emitEveryMillis); c.sendOrClosed(t); true
      }
    }
    c

  /** If this source has no elements then elements from an `alternative` source are emitted to the returned channel. If this source is
    * failed then failure is passed to the returned channel.
    *
    * @param alternative
    *   An alternative source of elements used when this source is empty.
    * @return
    *   A source that emits either elements from this source or from `alternative` (when this source is empty).
    * @example
    *   {{{
    *   import ox.*
    *   import ox.channels.Source
    *
    *   supervised {
    *     Source.fromValues(1).orElse(Source.fromValues(2, 3)).toList // List(1)
    *     Source.empty.orElse(Source.fromValues(2, 3)).toList         // List(2, 3)
    *   }
    *   }}}
    */
  def orElse[U >: T](alternative: Source[U])(using Ox, StageCapacity): Source[U] =
    val c = StageCapacity.newChannel[U]
    fork {
      receiveOrClosed() match
        case ChannelClosed.Done     => alternative.pipeTo(c)
        case ChannelClosed.Error(r) => c.errorOrClosed(r)
        case t: T @unchecked        => c.sendOrClosed(t); pipeTo(c)
    }
    c
}
