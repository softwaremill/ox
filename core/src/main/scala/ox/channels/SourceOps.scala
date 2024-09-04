package ox.channels

import com.softwaremill.jox.Source as JSource
import ox.*
import ox.channels.ChannelClosedUnion.isValue

import java.util
import java.util.concurrent.{CountDownLatch, Semaphore}
import scala.collection.IterableOnce
import scala.concurrent.duration.*

private[channels] case object GroupingTimeout
private[channels] case object NotSent

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
  def tapAsView(f: T => Unit): Source[T] = mapAsView(t => { f(t); t })

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
  def tap(f: T => Unit)(using Ox, StageCapacity): Source[T] = map(t => { f(t); t })

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

  /** Transform the source so that it returns elements as long as predicate `f` is satisfied (returns `true`). If `includeFirstFailing` is
    * `true`, the returned source will additionally return the first element that failed the predicate. After that, the source will complete
    * as Done.
    *
    * @param f
    *   A predicate function called on incoming elements. If it throws an exception, the result `Source` will be failed with that exception.
    * @param includeFirstFailing
    *   Whether the source should also emit the first element that failed the predicate (`false` by default).
    * @example
    *   {{{
    *   import ox.*
    *   import ox.channels.Source
    *
    *   supervised {
    *     Source.empty[Int].takeWhile(_ > 3).toList          // List()
    *     Source.fromValues(1, 2, 3).takeWhile(_ < 3).toList // List(1, 2)
    *     Source.fromValues(1, 2, 3, 4).takeWhile(_ < 3, includeFirstFailing = true).toList // List(1, 2, 3)
    *     Source.fromValues(3, 2, 1).takeWhile(_ < 3).toList // List()
    *   }
    *   }}}
    */
  def takeWhile(f: T => Boolean, includeFirstFailing: Boolean = false)(using Ox, StageCapacity): Source[T] =
    val c = StageCapacity.newChannel[T]
    forkPropagate(c) {
      repeatWhile {
        receiveOrClosed() match
          case ChannelClosed.Done          => c.done(); false
          case ChannelClosed.Error(reason) => c.error(reason); false
          case t: T @unchecked =>
            if f(t) then
              c.send(t)
              true
            else
              if includeFirstFailing then c.send(t)
              c.done()
              false
      }
    }
    c

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

  /** Pipes the elements of child sources into the output source. If the parent source or any of the child sources emit an error, the
    * pulling stops and the output source emits the error.
    */
  def flatten[U](using Ox, StageCapacity, T <:< Source[U]): Source[U] = {
    val c2 = StageCapacity.newChannel[U]
    case class Nested(child: Source[U])

    forkPropagate(c2) {
      val childStream = this.mapAsView(Nested(_))
      var pool = List[Source[Nested] | Source[U]](childStream)
      repeatWhile {
        selectOrClosed(pool) match {
          case ChannelClosed.Done =>
            // TODO: optimization idea: find a way to remove the specific channel that signalled to be Done
            pool = pool.filterNot(_.isClosedForReceiveDetail.contains(ChannelClosed.Done))
            if pool.isEmpty then
              c2.doneOrClosed()
              false
            else true
          case ChannelClosed.Error(e) =>
            c2.errorOrClosed(e)
            false
          case Nested(t) =>
            pool = t :: pool
            true
          case r: U @unchecked =>
            c2.sendOrClosed(r).isValue
        }
      }
    }

    c2
  }

  /** Concatenates this source with the `other` source. The resulting source will emit elements from this source first, and then from the
    * `other` source.
    *
    * @param other
    *   The source to be appended to this source.
    * @example
    *   {{{
    *   import ox.*
    *   import ox.channels.Source
    *
    *   supervised {
    *     Source.fromValues(1, 2).concat(Source.fromValues(3, 4)).toList // List(1, 2, 3, 4)
    *   }
    *   }}}
    */
  def concat[U >: T](other: Source[U])(using Ox, StageCapacity): Source[U] =
    Source.concat(List(() => this, () => other))

  /** Prepends `other` source to this source. The resulting source will emit elements from `other` source first, and then from the this
    * source.
    *
    * @param other
    *   The source to be prepended to this source.
    * @example
    *   {{{
    *   import ox.*
    *   import ox.channels.Source
    *
    *   supervised {
    *     Source.fromValues(1, 2).prepend(Source.fromValues(3, 4)).toList // List(3, 4, 1, 2)
    *   }
    *   }}}
    */
  def prepend[U >: T](other: Source[U])(using Ox, StageCapacity): Source[U] =
    Source.concat(List(() => other, () => this))

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
    forkPropagate(c) {
      var state = initializeState()
      repeatWhile {
        receiveOrClosed() match
          case ChannelClosed.Done =>
            onComplete(state).foreach(c.send)
            c.done()
            false
          case ChannelClosed.Error(r) =>
            c.error(r)
            false
          case t: T @unchecked =>
            val (nextState, result) = f(state, t)
            state = nextState
            result.iterator.foreach(c.send)
            true
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
    forkPropagate(c) {
      repeatWhile {
        receiveOrClosed() match
          case ChannelClosed.Done =>
            c.done()
            false
          case ChannelClosed.Error(r) =>
            c.error(r)
            false
          case t: T @unchecked =>
            val results: IterableOnce[U] = f(t)
            results.iterator.foreach(c.send)
            true
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

  /** Chunks up the elements into groups of the specified size. The last group may be smaller due to channel being closed. If this source is
    * failed then failure is passed to the returned channel.
    *
    * @param n
    *   The number of elements in a group.
    * @return
    *   A source that emits grouped elements.
    * @example
    *   {{{
    *   import ox.*
    *   import ox.channels.Source
    *
    *   supervised {
    *     Source.fromValues(1, 2, 3, 4, 5, 6, 7).grouped(3).toList // List(Seq(1, 2, 3), Seq(4, 5, 6), Seq(7))
    *   }
    *   }}}
    */
  def grouped(n: Int)(using Ox, StageCapacity): Source[Seq[T]] = groupedWeighted(n)(_ => 1)

  /** Chunks up the elements into groups that have a cumulative weight greater or equal to the `minWeight`. The last group may be smaller
    * due to channel being closed. If this source is failed then failure is passed to the returned channel.
    *
    * Upstream error or exception thrown by costFn will result in this Source failing with that error.
    *
    * @param minWeight
    *   The minimum cumulative weight of elements in a group.
    * @param costFn
    *   The function that calculates the weight of an element.
    * @return
    *   A source that emits grouped elements.
    * @example
    *   {{{
    *   import ox.*
    *   import ox.channels.Source
    *
    *   supervised {
    *     Source.fromValues(1, 2, 3, 4, 5, 6, 7).groupedWeighted(10)(n => n * 2).toList // List(Seq(1, 2, 3), Seq(4, 5), Seq(6), Seq(7))
    *   }
    *   }}}
    */
  def groupedWeighted(minWeight: Long)(costFn: T => Long)(using Ox, StageCapacity): Source[Seq[T]] =
    require(minWeight > 0, "minWeight must be > 0")
    val c2 = StageCapacity.newChannel[Seq[T]]
    forkPropagate(c2) {
      var buffer = Vector.empty[T]
      var accumulatedCost = 0L
      repeatWhile {
        receiveOrClosed() match
          case ChannelClosed.Done =>
            if buffer.nonEmpty then c2.send(buffer)
            c2.done()
            false
          case ChannelClosed.Error(r) =>
            c2.error(r)
            false
          case t: T @unchecked =>
            buffer = buffer :+ t

            accumulatedCost += costFn(t)

            if accumulatedCost >= minWeight then
              c2.send(buffer)
              buffer = Vector.empty
              accumulatedCost = 0

            true
      }
    }
    c2

  /** Chunks up the elements into groups received within a time window or limited by the specified number of elements, whatever happens
    * first. The timeout is reset after a group is emitted. If timeout expires and the buffer is empty, nothing is emitted. As soon as a new
    * element is received, the source will emit it as a single-element group and reset the timer.
    *
    * If this source is failed then failure is passed to the returned channel.
    *
    * @param n
    *   The maximum number of elements in a group.
    * @param duration
    *   The time window in which the elements are grouped.
    * @return
    *   A source that emits grouped elements.
    * @example
    *   {{{
    *   import ox.*
    *   import ox.channels.Source
    *
    *   supervised {
    *     val c = StageCapacity.newChannel[Int]
    *     fork {
    *       c.send(1)
    *       c.send(2)
    *       sleep(200.millis)
    *       c.send(3)
    *       c.send(4)
    *       c.send(5)
    *       c.send(6)
    *       c.done()
    *     }
    *     c.groupedWithin(3, 100.millis).toList // List(Seq(1, 2), Seq(3, 4, 5), Seq(6))
    *   }
    *   }}}
    */
  def groupedWithin(n: Int, duration: FiniteDuration)(using Ox, StageCapacity): Source[Seq[T]] = groupedWeightedWithin(n, duration)(_ => 1)

  /** Chunks up the elements into groups received within a time window or limited by the cumulative weight being greater or equal to the
    * `minWeight`, whatever happens first. The timeout is reset after a group is emitted. If timeout expires and the buffer is empty,
    * nothing is emitted. As soon as a new element is received, the source will emit it as a single-element group and reset the timer.
    *
    * Upstream error or exception thrown by costFn will result in this Source failing with that error.
    *
    * @param minWeight
    *   The minimum cumulative weight of elements in a group if no timeout happens.
    * @param duration
    *   The time window in which the elements are grouped.
    * @param costFn
    *   The function that calculates the weight of an element.
    * @return
    *   A source that emits grouped elements.
    * @example
    *   {{{
    *   import ox.*
    *   import ox.channels.Source
    *
    *   supervised {
    *     val c = StageCapacity.newChannel[Int]
    *     fork {
    *       c.send(1)
    *       c.send(2)
    *       sleep(200.millis)
    *       c.send(3)
    *       c.send(4)
    *       c.send(5)
    *       c.send(6)
    *       c.done()
    *     }
    *     c.groupedWeightedWithin(10, 100.millis)(n => n * 2).toList // List(Seq(1, 2), Seq(3, 4), Seq(5), Seq(6))
    *   }
    *   }}}
    */
  def groupedWeightedWithin(minWeight: Long, duration: FiniteDuration)(costFn: T => Long)(using Ox, StageCapacity): Source[Seq[T]] =
    require(minWeight > 0, "minWeight must be > 0")
    require(duration > 0.seconds, "duration must be > 0")
    val c2 = StageCapacity.newChannel[Seq[T]]
    val timerChannel = StageCapacity.newChannel[GroupingTimeout.type]
    forkPropagate(c2) {
      var buffer = Vector.empty[T]
      var accumulatedCost: Long = 0

      def forkTimeout() = forkCancellable {
        sleep(duration)
        timerChannel.sendOrClosed(GroupingTimeout).discard
      }
      var timeoutFork: Option[CancellableFork[Unit]] = Some(forkTimeout())

      def sendBufferAndForkNewTimeout(): Unit =
        c2.send(buffer)
        buffer = Vector.empty
        accumulatedCost = 0
        timeoutFork.foreach(_.cancelNow())
        timeoutFork = Some(forkTimeout())

      repeatWhile {
        selectOrClosed(receiveClause, timerChannel.receiveClause) match
          case ChannelClosed.Done =>
            timeoutFork.foreach(_.cancelNow())
            if buffer.nonEmpty then c2.send(buffer)
            c2.done()
            false
          case ChannelClosed.Error(r) =>
            timeoutFork.foreach(_.cancelNow())
            c2.error(r)
            false
          case timerChannel.Received(GroupingTimeout) =>
            timeoutFork = None // enter 'timed out state', may stay in this state if buffer is empty
            if buffer.nonEmpty then sendBufferAndForkNewTimeout()
            true
          case Received(t) =>
            buffer = buffer :+ t

            accumulatedCost += costFn(t).tapException(_ => timeoutFork.foreach(_.cancelNow()))

            if (timeoutFork.isEmpty || accumulatedCost >= minWeight) then
              // timeout passed when buffer was empty or buffer full
              sendBufferAndForkNewTimeout()

            true
      }
    }
    c2

  /** Creates sliding windows of elements from this source. The window slides by `step` elements. The last window may be smaller due to
    * channel being closed.
    *
    * If this source is failed then failure is passed to the returned channel.
    *
    * @param n
    *   The number of elements in a window.
    * @param step
    *   The number of elements the window slides by.
    * @return
    *   A source that emits grouped elements.
    * @example
    *   {{{
    *   import ox.*
    *   import ox.channels.Source
    *
    *   supervised {
    *     Source.fromValues(1, 2, 3, 4, 5, 6).sliding(3, 2).toList // List(Seq(1, 2, 3), Seq(3, 4, 5), Seq(5, 6))
    *   }
    *   }}}
    */
  def sliding(n: Int, step: Int = 1)(using Ox, StageCapacity): Source[Seq[T]] =
    require(n > 0, "n must be > 0")
    require(step > 0, "step must be > 0")
    val c = StageCapacity.newChannel[Seq[T]]
    fork {
      var buffer = Vector.empty[T]
      repeatWhile {
        receiveOrClosed() match
          case ChannelClosed.Done =>
            // send the remaining elements, only if these elements were not yet sent
            if buffer.nonEmpty && buffer.size < n then c.sendOrClosed(buffer).discard
            c.doneOrClosed()
            false
          case ChannelClosed.Error(r) =>
            c.errorOrClosed(r)
            false
          case t: T @unchecked =>
            buffer = buffer :+ t
            if buffer.size < n then true // do nothing
            else if buffer.size == n then c.sendOrClosed(buffer).isValue
            else if step <= n then
              // if step is <= n we simply drop `step` elements and continue appending until buffer size is n
              buffer = buffer.drop(step)
              // in special case when step == 1, we have to send the buffer immediately
              if buffer.size == n then c.sendOrClosed(buffer).isValue
              else true
            else
              // step > n -  we drop `step` elements and continue appending until buffer size is n
              if buffer.size == step then buffer = buffer.drop(step)
              true
      }
    }
    c

  /** Attaches the given Sink to this Source, meaning elements that pass through will also be sent to the Sink. If sending to the output
    * channel or the `other` Sink blocks, no elements will be processed until both channels can receive elements again. The source elements
    * are first sent to the output channel and then, only if the sent is successful, to the `other` Sink.
    *
    * If this source is failed, then failure is passed to the returned channel and the `other` Sink. If the `other` sink fails or closes,
    * then failure or closure is passed to the returned channel as well (contrary to [[alsoToTap]] where it's ignored).
    *
    * @param other
    *   The Sink to which elements from this source will be sent.
    * @return
    *   A source that emits input elements.
    * @example
    *   {{{
    *   import ox.*
    *   import ox.channels.Source
    *
    *   supervised {
    *     val c = Channel.withCapacity[Int](10)
    *     Source.fromValues(1, 2, 3).alsoTo(c).toList // List(1, 2, 3)
    *     c.toList // List(1, 2, 3)
    *   }
    *   }}}
    * @see
    *   [[alsoToTap]] for a version that drops elements when the `other` Sink is not available for receive.
    */
  def alsoTo[U >: T](other: Sink[U])(using Ox, StageCapacity): Source[U] =
    val c2 = StageCapacity.newChannel[U]
    fork {
      repeatWhile {
        receiveOrClosed() match
          case ChannelClosed.Done =>
            c2.doneOrClosed()
            other.doneOrClosed()
            false
          case ChannelClosed.Error(r) =>
            c2.errorOrClosed(r)
            other.errorOrClosed(r)
            false
          case t: T @unchecked =>
            propagateOrElse(c2.sendOrClosed(t), other) {
              propagateOrElse(other.sendOrClosed(t), c2)(true)
            }
      }
    }
    c2

  /** Attaches the given Sink to this Source, meaning elements that pass through will also be sent to the Sink. If the `other` Sink is not
    * available for receive, the elements are still sent to returned channel, but not to the `other` Sink, meaning that some elements may be
    * dropped.
    *
    * If this source is failed, then failure is passed to the returned channel and the `other` Sink. If the `other` sink fails or closes,
    * then failure or closure is ignored and it doesn't affect the resulting source (contrary to [[alsoTo]] where it's propagated).
    *
    * @param other
    *   The Sink to which elements from this source will be sent.
    * @return
    *   A source that emits input elements.
    * @example
    *   {{{
    *   import ox.*
    *   import ox.channels.Source
    *
    *   supervised {
    *     val c = Channel.withCapacity[Int](10)
    *     Source.fromValues(1, 2, 3).alsoToTap(c).toList // List(1, 2, 3)
    *     c.toList // List(1, 2, 3) but not guaranteed
    *   }
    *   }}}
    * @see
    *   [[alsoTo]] for a version that ensures that elements are sent to both the output and the `other` Sink.
    */
  def alsoToTap[U >: T](other: Sink[U])(using Ox, StageCapacity): Source[U] =
    val c2 = StageCapacity.newChannel[U]
    fork {
      repeatWhile {
        receiveOrClosed() match
          case ChannelClosed.Done =>
            c2.doneOrClosed()
            other.doneOrClosed()
            false
          case ChannelClosed.Error(r) =>
            c2.errorOrClosed(r)
            other.errorOrClosed(r)
            false
          case t: T @unchecked =>
            propagateOrElse(c2.sendOrClosed(t), other) {
              selectOrClosed(other.sendClause(t), Default(NotSent)).discard; true
            }
      }
    }
    c2

  private inline def propagateOrElse(unitOrClosed: Unit | ChannelClosed, otherSink: Sink[T])(inline ifNotClosed: Boolean): Boolean =
    unitOrClosed match
      case ChannelClosed.Done     => otherSink.doneOrClosed().discard; false
      case ChannelClosed.Error(r) => otherSink.errorOrClosed(r).discard; false
      case _                      => ifNotClosed
}
