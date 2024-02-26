package ox.channels

import com.softwaremill.jox.Source as JSource
import ox.*

import java.util
import java.util.concurrent.{CountDownLatch, Semaphore}
import scala.collection.{IterableOnce, mutable}
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, ExecutionException, Future}
import scala.util.{Failure, Success}

trait SourceOps[+T] { outer: Source[T] =>
  // view ops (lazy)

  /** Lazily-evaluated map: creates a view of this source, where the results of [[receive]] will be transformed using the given function
    * `f`. For an eager version, see [[map]].
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

  /** Lazily-evaluated filter: Creates a view of this source, where the results of [[receive]] will be filtered using the given predicate
    * `p`. For an eager version, see [[filter]].
    *
    * The same logic applies to receive clauses created using this source, which can be used in [[select]].
    *
    * @param p
    *   The predicate to use for filtering.
    * @return
    *   A source which is a view of this source, with the filtering function applied.
    */
  def filterAsView(f: T => Boolean): Source[T] = new Source[T] {
    override val delegate: JSource[Any] = outer.delegate.filterAsView(t => f(t.asInstanceOf[T]))
  }

  /** Creates a view of this source, where the results of [[receive]] will be transformed using the given function `f`. If the function is
    * not defined at an element, the element will be skipped.
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
        receive() match
          case ChannelClosed.Done     => c2.done(); false
          case ChannelClosed.Error(r) => c2.error(r); false
          case t: T @unchecked =>
            try
              val u = f(t)
              c2.send(u).isValue
            catch
              case t: Throwable =>
                c2.error(t)
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
      start.foreach(c.send)
      var firstEmitted = false
      repeatWhile {
        receive() match
          case ChannelClosed.Done               => end.foreach(c.send); c.done(); false
          case ChannelClosed.Error(e)           => c.error(e); false
          case v: U @unchecked if !firstEmitted => firstEmitted = true; c.send(v); true
          case v: U @unchecked                  => c.send(inject); c.send(v); true
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
    val inProgress = Channel[Fork[Option[U]]](parallelism)
    val closeScope = new CountDownLatch(1)
    scoped {
      // enqueueing fork
      fork {
        repeatWhile {
          s.acquire()
          receive() match
            case ChannelClosed.Done =>
              inProgress.done()
              false
            case ChannelClosed.Error(r) =>
              c2.error(r)
              // closing the scope, any child forks will be cancelled before the scope is done
              closeScope.countDown()
              false
            case t: T @unchecked =>
              inProgress.send(fork {
                try
                  val u = f(t)
                  s.release() // not in finally, as in case of an exception, no point in starting subsequent forks
                  Some(u)
                catch
                  case t: Throwable =>
                    c2.error(t)
                    closeScope.countDown()
                    None
              })
              true
        }
      }

      // sending fork
      fork {
        repeatWhile {
          inProgress.receive() match
            case f: Fork[Option[U]] @unchecked =>
              f.join().map(c2.send).isDefined
            case ChannelClosed.Done =>
              closeScope.countDown()
              c2.done()
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
          receive() match
            case ChannelClosed.Done => false
            case ChannelClosed.Error(r) =>
              c.error(r)
              false
            case t: T @unchecked =>
              forkUser {
                try
                  c.send(f(t))
                  s.release()
                catch case t: Throwable => c.error(t)
              }
              true
        }
      }
      c.done()
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
          val temp = receive()
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
        toDrain.receive() match
          case ChannelClosed.Done     => c.done(); false
          case ChannelClosed.Error(r) => c.error(r); false
          case t: U @unchecked        => c.send(t).isValue
      }

    fork {
      repeatWhile {
        select(this, other) match
          case ChannelClosed.Done =>
            if this.isClosedForReceive then drainFrom(other) else drainFrom(this)
            false
          case ChannelClosed.Error(r) => c.error(r); false
          case r: U @unchecked        => c.send(r).isValue
      }
    }
    c

  def concat[U >: T](other: Source[U])(using Ox, StageCapacity): Source[U] =
    Source.concat(List(() => this, () => other))

  def zip[U](other: Source[U])(using Ox, StageCapacity): Source[(T, U)] =
    val c = StageCapacity.newChannel[(T, U)]
    fork {
      repeatWhile {
        receive() match
          case ChannelClosed.Done     => c.done(); false
          case ChannelClosed.Error(r) => c.error(r); false
          case t: T @unchecked =>
            other.receive() match
              case ChannelClosed.Done     => c.done(); false
              case ChannelClosed.Error(r) => c.error(r); false
              case u: U @unchecked        => c.send(t, u).isValue
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
      other.receive() match
        case ChannelClosed.Done     => otherClosedHandler()
        case ChannelClosed.Error(r) => c.error(r); false
        case v: V @unchecked        => c.send(thisElement, v); true

    fork {
      repeatWhile {
        receive() match
          case ChannelClosed.Done     => receiveFromOther(thisDefault, () => { c.done(); false })
          case ChannelClosed.Error(r) => c.error(r); false
          case t: T @unchecked        => receiveFromOther(t, () => { c.send(t, otherDefault); true })
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
    *   If `true`, the returned channel is completed as soon as either of the sources completes. If 'false`, the remaining elements of the
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

  //

  /** Invokes the given function for each received element. Blocks until the channel is done.
    * @throws ChannelClosedException
    *   when there is an upstream error.
    */
  def foreach(f: T => Unit): Unit =
    repeatWhile {
      receive() match
        case ChannelClosed.Done     => false
        case e: ChannelClosed.Error => throw e.toThrowable
        case t: T @unchecked        => f(t); true
    }

  /** Accumulates all elements received from the channel into a list. Blocks until the channel is done.
    * @throws ChannelClosedException
    *   when there is an upstream error.
    */
  def toList: List[T] =
    val b = List.newBuilder[T]
    foreach(b += _)
    b.result()

  /** Passes each received element from this channel to the given sink. Blocks until the channel is done.
    * @throws ChannelClosedException
    *   when there is an upstream error, or when the sink is closed.
    */
  def pipeTo(sink: Sink[T]): Unit =
    repeatWhile {
      receive() match
        case ChannelClosed.Done     => sink.done(); false
        case e: ChannelClosed.Error => sink.error(e.reason); false
        case t: T @unchecked        => sink.send(t).orThrow; true
    }

  /** Receives all elements from the channel. Blocks until the channel is done.
    * @throws ChannelClosedException
    *   when there is an upstream error.
    */
  def drain(): Unit = foreach(_ => ())

  def applied[U](f: Source[T] => U): U = f(this)

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
  def mapStateful[S, U >: T](
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
  def mapStatefulConcat[S, U >: T](
      initializeState: () => S
  )(f: (S, T) => (S, IterableOnce[U]), onComplete: S => Option[U] = (_: S) => None)(using Ox, StageCapacity): Source[U] =
    val c = StageCapacity.newChannel[U]
    fork {
      var state = initializeState()
      repeatWhile {
        receive() match
          case ChannelClosed.Done =>
            try
              onComplete(state).foreach(c.send)
              c.done()
            catch case t: Throwable => c.error(t)
            false
          case ChannelClosed.Error(r) =>
            c.error(r)
            false
          case t: T @unchecked =>
            try
              val (nextState, result) = f(state, t)
              state = nextState
              result.iterator.map(c.send).forall(_.isValue)
            catch
              case t: Throwable =>
                c.error(t)
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
      receive() match
        case ChannelClosed.Done     => None
        case e: ChannelClosed.Error => throw e.toThrowable
        case t: T @unchecked        => Some(t)
    }

  /** Returns the first element from this source or throws [[NoSuchElementException]] when this source is empty. In case when receiving an
    * element fails with exception then [[ChannelClosedException.Error]] is thrown. Note that `head` is not an idempotent operation on
    * source as it receives elements from it.
    *
    * @return
    *   A first element if source is not empty or throws otherwise.
    * @throws NoSuchElementException
    *   When this source is empty.
    * @throws ChannelClosedException.Error
    *   When receiving an element from this source fails.
    * @example
    *   {{{
    *   import ox.*
    *   import ox.channels.Source
    *
    *   supervised {
    *     Source.empty[Int].head()        // throws NoSuchElementException("cannot obtain head element from an empty source")
    *     val s = Source.fromValues(1, 2)
    *     s.head()                        // 1
    *     s.head()                        // 2
    *   }
    *   }}}
    */
  def head(): T = headOption().getOrElse(throw new NoSuchElementException("cannot obtain head element from an empty source"))

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
    *   scoped {
    *     Source.empty[Int].throttle(1, 1.second).toList       // List() returned without throttling
    *     Source.fromValues(1, 2).throttle(1, 1.second).toList // List(1, 2) returned after 2 seconds
    *   }
    *   }}}
    */
  def throttle(elements: Int, per: FiniteDuration)(using Ox, StageCapacity): Source[T] =
    require(elements > 0, "elements must be > 0")
    require(per.toMillis > 0, "per time must be >= 1 ms")

    val c = StageCapacity.newChannel[T]
    val emitEveryMillis = per.toMillis / elements

    fork {
      repeatWhile {
        receive() match
          case ChannelClosed.Done     => c.done(); false
          case ChannelClosed.Error(r) => c.error(r); false
          case t: T @unchecked        => Thread.sleep(emitEveryMillis); c.send(t); true
      }
    }
    c

  /** Returns the last element from this source wrapped in [[Some]] or [[None]] when this source is empty. Note that `lastOption` is a
    * terminal operation leaving the source in [[ChannelClosed.Done]] state.
    *
    * @return
    *   A `Some(last element)` if source is not empty or `None` otherwise.
    * @throws ChannelClosedException.Error
    *   When receiving an element from this source fails.
    * @example
    *   {{{
    *   import ox.*
    *   import ox.channels.Source
    *
    *   supervised {
    *     Source.empty[Int].lastOption()  // None
    *     val s = Source.fromValues(1, 2)
    *     s.lastOption()                  // Some(2)
    *     s.receive()                     // ChannelClosed.Done
    *   }
    *   }}}
    */
  def lastOption(): Option[T] =
    supervised {
      var value: Option[T] = None
      repeatWhile {
        receive() match
          case ChannelClosed.Done     => false
          case e: ChannelClosed.Error => throw e.toThrowable
          case t: T @unchecked        => value = Some(t); true
      }
      value
    }

  /** Returns the last element from this source or throws [[NoSuchElementException]] when this source is empty. In case when receiving an
    * element fails then [[ChannelClosedException.Error]] exception is thrown. Note that `last` is a terminal operation leaving the source
    * in [[ChannelClosed.Done]] state.
    *
    * @return
    *   A last element if source is not empty or throws otherwise.
    * @throws NoSuchElementException
    *   When this source is empty.
    * @throws ChannelClosedException.Error
    *   When receiving an element from this source fails.
    * @example
    *   {{{
    *   import ox.*
    *   import ox.channels.Source
    *
    *   supervised {
    *     Source.empty[Int].last()        // throws NoSuchElementException("cannot obtain last element from an empty source")
    *     val s = Source.fromValues(1, 2)
    *     s.last()                        // 2
    *     s.receive()                     // ChannelClosed.Done
    *   }
    *   }}}
    */
  def last(): T = lastOption().getOrElse(throw new NoSuchElementException("cannot obtain last element from an empty source"))

  /** Uses `zero` as the current value and applies function `f` on it and a value received from this source. The returned value is used as
    * the next current value and `f` is applied again with the value received from a source. The operation is repeated until the source is
    * drained.
    *
    * @param zero
    *   An initial value to be used as the first argument to function `f` call.
    * @param f
    *   A binary function (a function that takes two arguments) that is applied to the current value and value received from a source.
    * @return
    *   Combined value retrieved from running function `f` on all source elements in a cumulative manner where result of the previous call
    *   is used as an input value to the next.
    * @throws ChannelClosedException.Error
    *   When receiving an element from this source fails.
    * @throws exception
    *   When function `f` throws an `exception` then it is propagated up to the caller.
    * @example
    *   {{{
    *   import ox.*
    *   import ox.channels.Source
    *
    *   supervised {
    *     Source.empty[Int].fold(0)((acc, n) => acc + n)       // 0
    *     Source.fromValues(2, 3).fold(5)((acc, n) => acc - n) // 0
    *   }
    *   }}}
    */
  def fold[U](zero: U)(f: (U, T) => U): U =
    var current = zero
    repeatWhile {
      receive() match
        case ChannelClosed.Done     => false
        case e: ChannelClosed.Error => throw e.toThrowable
        case t: T @unchecked        => current = f(current, t); true
    }
    current

  /** Uses the first and the following (if available) elements from this source and applies function `f` on them. The returned value is used
    * as the next current value and `f` is applied again with the value received from this source. The operation is repeated until this
    * source is drained. This is similar operation to [[fold]] but it uses the first source element as `zero`.
    *
    * @param f
    *   A binary function (a function that takes two arguments) that is applied to the current and next values received from this source.
    * @return
    *   Combined value retrieved from running function `f` on all source elements in a cumulative manner where result of the previous call
    *   is used as an input value to the next.
    * @throws NoSuchElementException
    *   When this source is empty.
    * @throws ChannelClosedException.Error
    *   When receiving an element from this source fails.
    * @throws exception
    *   When function `f` throws an `exception` then it is propagated up to the caller.
    * @example
    *   {{{
    *   import ox.*
    *   import ox.channels.Source
    *
    *   supervised {
    *     Source.empty[Int].reduce(_ + _)    // throws NoSuchElementException("cannot reduce an empty source")
    *     Source.fromValues(1).reduce(_ + _) // 1
    *     val s = Source.fromValues(1, 2)
    *     s.reduce(_ + _)                    // 3
    *     s.receive()                        // ChannelClosed.Done
    *   }
    *   }}}
    */
  def reduce[U >: T](f: (U, U) => U): U =
    fold(headOption().getOrElse(throw new NoSuchElementException("cannot reduce an empty source")))(f)

  /** Returns the list of up to `n` last elements from this source. Less than `n` elements is returned when this source contains less
    * elements than requested. The [[List.empty]] is returned when `takeLast` is called on an empty source.
    *
    * @param n
    *   Number of elements to be taken from the end of this source. It is expected that `n >= 0`.
    * @return
    *   A list of up to `n` last elements from this source.
    * @throws ChannelClosedException.Error
    *   When receiving an element from this source fails.
    * @example
    *   {{{
    *   import ox.*
    *   import ox.channels.Source
    *
    *   supervised {
    *     Source.empty[Int].takeLast(5)    // List.empty
    *     Source.fromValues(1).takeLast(0) // List.empty
    *     Source.fromValues(1).takeLast(2) // List(1)
    *     val s = Source.fromValues(1, 2, 3, 4)
    *     s.takeLast(2)                    // List(4, 5)
    *     s.receive()                      // ChannelClosed.Done
    *   }
    *   }}}
    */
  def takeLast(n: Int): List[T] =
    require(n >= 0, "n must be >= 0")
    if (n == 0)
      drain()
      List.empty
    else if (n == 1) lastOption().map(List(_)).getOrElse(List.empty)
    else
      supervised {
        val buffer: mutable.ListBuffer[T] = mutable.ListBuffer()
        buffer.sizeHint(n)
        repeatWhile {
          receive() match
            case ChannelClosed.Done     => false
            case e: ChannelClosed.Error => throw e.toThrowable
            case t: T @unchecked =>
              if (buffer.size == n) buffer.dropInPlace(1)
              buffer.append(t); true
        }
        buffer.result()
      }

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
      receive() match
        case ChannelClosed.Done     => alternative.pipeTo(c)
        case ChannelClosed.Error(r) => c.error(r)
        case t: T @unchecked        => c.send(t); pipeTo(c)
    }
    c
}

trait SourceCompanionOps:
  def fromIterable[T](it: Iterable[T])(using Ox, StageCapacity): Source[T] = fromIterator(it.iterator)

  def fromValues[T](ts: T*)(using Ox, StageCapacity): Source[T] = fromIterator(ts.iterator)

  def fromIterator[T](it: => Iterator[T])(using Ox, StageCapacity): Source[T] =
    val c = StageCapacity.newChannel[T]
    fork {
      val theIt = it
      try
        while theIt.hasNext do c.send(theIt.next())
        c.done()
      catch case t: Throwable => c.error(t)
    }
    c

  def fromFork[T](f: Fork[T])(using Ox, StageCapacity): Source[T] =
    val c = StageCapacity.newChannel[T]
    fork {
      try
        c.send(f.join())
        c.done()
      catch case t: Throwable => c.error(t)
    }
    c

  def iterate[T](zero: T)(f: T => T)(using Ox, StageCapacity): Source[T] =
    val c = StageCapacity.newChannel[T]
    fork {
      var t = zero
      try
        forever {
          c.send(t)
          t = f(t)
        }
      catch case t: Throwable => c.error(t)
    }
    c

  /** A range of number, from `from`, to `to` (inclusive), stepped by `step`. */
  def range(from: Int, to: Int, step: Int)(using Ox, StageCapacity): Source[Int] =
    val c = StageCapacity.newChannel[Int]
    fork {
      var t = from
      try
        repeatWhile {
          c.send(t)
          t = t + step
          t <= to
        }
        c.done()
      catch case t: Throwable => c.error(t)
    }
    c

  def unfold[S, T](initial: S)(f: S => Option[(T, S)])(using Ox, StageCapacity): Source[T] =
    val c = StageCapacity.newChannel[T]
    fork {
      var s = initial
      try
        repeatWhile {
          f(s) match
            case Some((value, next)) =>
              c.send(value)
              s = next
              true
            case None =>
              c.done()
              false
        }
      catch case t: Throwable => c.error(t)
    }
    c

  def tick[T](interval: FiniteDuration, element: T = ())(using Ox, StageCapacity): Source[T] =
    val c = StageCapacity.newChannel[T]
    fork {
      forever {
        c.send(element)
        Thread.sleep(interval.toMillis)
      }
    }
    c

  def repeat[T](element: T = ())(using Ox, StageCapacity): Source[T] =
    val c = StageCapacity.newChannel[T]
    fork {
      forever {
        c.send(element)
      }
    }
    c

  def timeout[T](interval: FiniteDuration, element: T = ())(using Ox, StageCapacity): Source[T] =
    val c = StageCapacity.newChannel[T]
    fork {
      Thread.sleep(interval.toMillis)
      c.send(element)
      c.done()
    }
    c

  def concat[T](sources: Seq[() => Source[T]])(using Ox, StageCapacity): Source[T] =
    val c = StageCapacity.newChannel[T]
    fork {
      var currentSource: Option[Source[T]] = None
      val sourcesIterator = sources.iterator
      var continue = true
      try
        while continue do
          currentSource match
            case None if sourcesIterator.hasNext => currentSource = Some(sourcesIterator.next()())
            case None =>
              c.done()
              continue = false
            case Some(source) =>
              source.receive() match
                case ChannelClosed.Done =>
                  currentSource = None
                case ChannelClosed.Error(r) =>
                  c.error(r)
                  continue = false
                case t: T @unchecked =>
                  c.send(t)
      catch case t: Throwable => c.error(t)
    }
    c

  def empty[T]: Source[T] =
    val c = Channel.rendezvous[T]
    c.done()
    c

  /** Sends a given number of elements (determined byc `segmentSize`) from each source in `sources` to the returned channel and repeats. The
    * order of elements in all sources is preserved.
    *
    * If any of the sources is done before the others, the behavior depends on the `eagerCancel` flag. When set to `true`, the returned
    * channel is completed immediately, otherwise the interleaving continues with the remaining non-completed sources. Once all but one
    * sources are complete, the elements of the remaining non-complete source are sent to the returned channel.
    *
    * Must be run within a scope, since a child fork is created which receives from the subsequent sources and sends to the resulting
    * channel.
    *
    * @param sources
    *   The sources whose elements will be interleaved.
    * @param segmentSize
    *   The number of elements sent from each source before switching to the next one. Default is 1.
    * @param eagerComplete
    *   If `true`, the returned channel is completed as soon as any of the sources completes. If 'false`, the interleaving continues with
    *   the remaining non-completed sources.
    * @return
    *   A source to which the interleaved elements from both sources would be sent.
    * @example
    *   {{{
    *   scala>
    *   import ox.*
    *   import ox.channels.Source
    *
    *   supervised {
    *     val s1 = Source.fromValues(1, 2, 3, 4, 5, 6, 7, 8)
    *     val s2 = Source.fromValues(10, 20, 30)
    *     val s3 = Source.fromValues(100, 200, 300, 400, 500)
    *     Source.interleaveAll(List(s1, s2, s3), segmentSize = 2, eagerComplete = true).toList
    *   }
    *
    *   scala> val res0: List[Int] = List(1, 2, 10, 20, 100, 200, 3, 4, 30)
    *   }}}
    */
  def interleaveAll[T](sources: Seq[Source[T]], segmentSize: Int = 1, eagerComplete: Boolean = false)(using
      Ox,
      StageCapacity
  ): Source[T] =
    sources match
      case Nil           => Source.empty
      case single :: Nil => single
      case _ =>
        val c = StageCapacity.newChannel[T]

        fork {
          val availableSources = mutable.ArrayBuffer.from(sources)
          var currentSourceIndex = 0
          var elementsRead = 0

          def completeCurrentSource(): Unit =
            availableSources.remove(currentSourceIndex)
            currentSourceIndex = if (currentSourceIndex == 0) availableSources.size - 1 else currentSourceIndex - 1

          def switchToNextSource(): Unit =
            currentSourceIndex = (currentSourceIndex + 1) % availableSources.size
            elementsRead = 0

          repeatWhile {
            availableSources(currentSourceIndex).receive() match
              case ChannelClosed.Done =>
                completeCurrentSource()

                if (eagerComplete || availableSources.isEmpty)
                  c.done()
                  false
                else
                  switchToNextSource()
                  true
              case ChannelClosed.Error(r) =>
                c.error(r)
                false
              case value: T @unchecked =>
                elementsRead += 1
                // after reaching segmentSize, only switch to next source if there's any other available
                if (elementsRead == segmentSize && availableSources.size > 1) switchToNextSource()
                c.send(value).isValue
          }
        }
        c

  /** Creates a source that emits a single value when `from` completes or fails otherwise. The `from` completion is performed on the
    * provided [[scala.concurrent.ExecutionContext]]. Note that when `from` fails with [[scala.concurrent.ExecutionException]] then its
    * cause is returned as source failure.
    *
    * @param from
    *   A [[scala.concurrent.Future]] that returns value upon completion.
    * @return
    *   A source that will emit value upon a `from` [[scala.concurrent.Future]] completion.
    * @example
    *   {{{
    *   import ox.*
    *   import ox.channels.Source
    *
    *   import scala.concurrent.ExecutionContext.Implicits.global
    *   import scala.concurrent.Future
    *
    *   supervised {
    *     Source
    *       .future(Future.failed(new RuntimeException("future failed")))
    *       .receive()                               // ChannelClosed.Error(java.lang.RuntimeException: future failed)
    *     Source.future(Future.successful(1)).toList // List(1)
    *   }
    *   }}}
    */
  def future[T](from: Future[T])(using StageCapacity, ExecutionContext): Source[T] =
    val c = StageCapacity.newChannel[T]
    receiveAndSendFromFuture(from, c)
    c

  /** Creates a source that emits elements from future source when `from` completes or fails otherwise. The `from` completion is performed
    * on the provided [[scala.concurrent.ExecutionContext]] whereas elements are emitted through Ox. Note that when `from` fails with
    * [[scala.concurrent.ExecutionException]] then its cause is returned as source failure.
    *
    * @param from
    *   A [[scala.concurrent.Future]] that returns source upon completion.
    * @return
    *   A source that will emit values upon a `from` [[scala.concurrent.Future]] completion.
    * @example
    *   {{{
    *   import ox.*
    *   import ox.channels.Source
    *
    *   import scala.concurrent.ExecutionContext.Implicits.global
    *   import scala.concurrent.Future
    *
    *   supervised {
    *     Source
    *       .futureSource(Future.failed(new RuntimeException("future failed")))
    *       .receive()                                                           // ChannelClosed.Error(java.lang.RuntimeException: future failed)
    *     Source.futureSource(Future.successful(Source.fromValues(1, 2))).toList // List(1, 2)
    *   }
    *   }}}
    */
  def futureSource[T](from: Future[Source[T]])(using Ox, StageCapacity, ExecutionContext): Source[T] =
    val c = StageCapacity.newChannel[T]
    val transportChannel = StageCapacity.newChannel[Source[T]](using StageCapacity(1))

    receiveAndSendFromFuture(from, transportChannel)

    fork {
      transportChannel.receive() match
        case ChannelClosed.Done           => c.done()
        case ChannelClosed.Error(r)       => c.error(r)
        case source: Source[T] @unchecked => source.pipeTo(c)
    }
    c

  private def receiveAndSendFromFuture[T](from: Future[T], to: Channel[T])(using ExecutionContext): Unit = {
    from.onComplete {
      case Success(value)                  => to.send(value); to.done()
      case Failure(ex: ExecutionException) => to.error(ex.getCause)
      case Failure(ex)                     => to.error(ex)
    }
  }

  /** Creates a source that fails immediately with the given [[java.lang.Throwable]]
    *
    * @param t
    *   The [[java.lang.Throwable]] to fail with
    * @return
    *   A source that would fail immediately with the given [[java.lang.Throwable]]
    */
  def failed[T](t: Throwable): Source[T] =
    val c = Channel.rendezvous[T]
    c.error(t)
    c
