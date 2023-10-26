package ox.channels

import ox.*

import java.util.concurrent.{CountDownLatch, Semaphore}
import scala.collection.{IterableOnce, mutable}
import scala.concurrent.duration.FiniteDuration
import scala.util.Try

trait SourceOps[+T] { this: Source[T] =>
  // view ops (lazy)

  def mapAsView[U](f: T => U): Source[U] = CollectSource(this, t => Some(f(t)))
  def filterAsView(f: T => Boolean): Source[T] = CollectSource(this, t => if f(t) then Some(t) else None)
  def collectAsView[U](f: PartialFunction[T, U]): Source[U] = CollectSource(this, f.lift)

  // run ops (eager)

  def map[U](f: T => U)(using Ox, StageCapacity): Source[U] =
    val c2 = StageCapacity.newChannel[U]
    forkDaemon {
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
    *   scoped {
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
    *   scoped {
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
    forkDaemon {
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
    forkDaemon(mapParScope(parallelism, c2, f))
    c2

  private def mapParScope[U](parallelism: Int, c2: Channel[U], f: T => U): Unit =
    val s = new Semaphore(parallelism)
    val inProgress = Channel[Fork[U]](parallelism)
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
                  u
                catch
                  case t: Throwable =>
                    c2.error(t)
                    closeScope.countDown()
                    throw t
              })
              true
        }
      }

      // sending fork
      fork {
        repeatWhile {
          inProgress.receive() match
            case f: Fork[U] @unchecked =>
              c2.send(f.join()).isValue
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
    forkDaemon {
      supervised {
        repeatWhile {
          s.acquire()
          receive() match
            case ChannelClosed.Done => false
            case e @ ChannelClosed.Error(r) =>
              c.error(r)
              false
            case t: T @unchecked =>
              fork {
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
    *   scoped {
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
    *   scoped {
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
    forkDaemon {
      repeatWhile {
        select(this, other) match
          case ChannelClosed.Done     => c.done(); false
          case ChannelClosed.Error(r) => c.error(r); false
          case r: U @unchecked        => c.send(r).isValue
      }
    }
    c

  def concat[U >: T](other: Source[U])(using Ox, StageCapacity): Source[U] =
    Source.concat(List(() => this, () => other))

  def zip[U](other: Source[U])(using Ox, StageCapacity): Source[(T, U)] =
    val c = StageCapacity.newChannel[(T, U)]
    forkDaemon {
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
    *   scoped {
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

    forkDaemon {
      repeatWhile {
        receive() match
          case ChannelClosed.Done     => receiveFromOther(thisDefault, () => { c.done(); false })
          case ChannelClosed.Error(r) => c.error(r); false
          case t: T @unchecked        => receiveFromOther(t, () => { c.send(t, otherDefault); true })
      }
    }
    c

  //

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
    *   scoped {
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
        case e: ChannelClosed.Error => sink.error(e.reason); throw e.toThrowable
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
    *   scoped {
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
    *   scoped {
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
    forkDaemon {
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

  /** Returns the first element from this source wrapped in `Some` or `None` when the source is empty or fails during the receive operation.
    * Note that `headOption` is not an idempotent operation on source as it receives elements from it.
    *
    * @return
    *   A `Some(first element)` if source is not empty or None` otherwise.
    * @example
    *   {{{
    *   import ox.*
    *   import ox.channels.Source
    *
    *   scoped {
    *     Source.empty[Int].headOption()  // None
    *     val s = Source.fromValues(1, 2)
    *     s.headOption()                  // Some(1)
    *     s.headOption()                  // Some(2)
    *   }
    *   }}}
    */
  def headOption(): Option[T] = Try(head()).toOption

  /** Returns the first element from this source or throws `NoSuchElementException` when the source is empty or `receive()` operation fails
    * without error. In case when the `receive()` operation fails with exception that exception is re-thrown. Note that `headOption` is not
    * an idempotent operation on source as it receives elements from it.
    *
    * @return
    *   A first element if source is not empty or throws otherwise.
    * @throws NoSuchElementException
    *   When source is empty or `receive()` failed without error.
    * @throws exception
    *   When `receive()` failed with exception then this exception is re-thrown.
    * @example
    *   {{{
    *   import ox.*
    *   import ox.channels.Source
    *
    *   scoped {
    *     Source.empty[Int].head()        // throws NoSuchElementException("cannot obtain head from an empty source")
    *     val s = Source.fromValues(1, 2)
    *     s.head()                        // 1
    *     s.head()                        // 2
    *   }
    *   }}}
    */
  def head(): T =
    supervised {
      receive() match
        case ChannelClosed.Done     => throw new NoSuchElementException("cannot obtain head from an empty source")
        case ChannelClosed.Error(r) => throw r.getOrElse(new NoSuchElementException("getting head failed"))
        case t: T @unchecked        => t
    }
}

trait SourceCompanionOps:
  def fromIterable[T](it: Iterable[T])(using Ox, StageCapacity): Source[T] = fromIterator(it.iterator)

  def fromValues[T](ts: T*)(using Ox, StageCapacity): Source[T] = fromIterator(ts.iterator)

  def fromIterator[T](it: => Iterator[T])(using Ox, StageCapacity): Source[T] =
    val c = StageCapacity.newChannel[T]
    forkDaemon {
      val theIt = it
      try
        while theIt.hasNext do c.send(theIt.next())
        c.done()
      catch case t: Throwable => c.error(t)
    }
    c

  def fromFork[T](f: Fork[T])(using Ox, StageCapacity): Source[T] =
    val c = StageCapacity.newChannel[T]
    forkDaemon {
      try
        c.send(f.join())
        c.done()
      catch case t: Throwable => c.error(t)
    }
    c

  def iterate[T](zero: T)(f: T => T)(using Ox, StageCapacity): Source[T] =
    val c = StageCapacity.newChannel[T]
    forkDaemon {
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
    forkDaemon {
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
    forkDaemon {
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
    forkDaemon {
      forever {
        c.send(element)
        Thread.sleep(interval.toMillis)
      }
    }
    c

  def repeat[T](element: T = ())(using Ox, StageCapacity): Source[T] =
    val c = StageCapacity.newChannel[T]
    forkDaemon {
      forever {
        c.send(element)
      }
    }
    c

  def timeout[T](interval: FiniteDuration, element: T = ())(using Ox, StageCapacity): Source[T] =
    val c = StageCapacity.newChannel[T]
    forkDaemon {
      Thread.sleep(interval.toMillis)
      c.send(element)
      c.done()
    }
    c

  def concat[T](sources: Seq[() => Source[T]])(using Ox, StageCapacity): Source[T] =
    val c = StageCapacity.newChannel[T]
    forkDaemon {
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
    val c = DirectChannel()
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
    *   scoped {
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

        forkDaemon {
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

  /** Creates a source that fails immediately with the given [[java.lang.Throwable]]
    *
    * @param t
    *   The [[java.lang.Throwable]] to fail with
    * @return
    *   A source that would fail immediately with the given [[java.lang.Throwable]]
    */
  def failed[T](t: Throwable): Source[T] =
    val c = DirectChannel[T]()
    c.error(t)
    c

  /** Creates a source that fails immediately
    *
    * @return
    *   A source that would fail immediately
    */
  private[channels] def failedWithoutReason[T](): Source[T] =
    val c = DirectChannel[T]()
    c.error(None)
    c
