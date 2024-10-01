package ox.flow

import ox.Ox
import java.util.concurrent.Semaphore
import ox.channels.Channel
import ox.Fork
import ox.forkUnsupervised
import ox.channels.ChannelClosed
import ox.repeatWhile
import ox.unsupervised
import ox.discard
import ox.OxUnsupervised
import ox.channels.forkPropagate
import ox.supervised
import ox.channels.forkUserPropagate

class FlowOps[+T]:
  outer: Flow[T] =>

  def map[U](f: T => U): Flow[U] = addTransformSinkStage(next => FlowSink.propagateClose(next)(t => next.onNext(f(t))))

  def filter(f: T => Boolean): Flow[T] = addTransformSinkStage(next => FlowSink.propagateClose(next)(t => if f(t) then next.onNext(t)))

  /** Applies the given mapping function `f` to each element emitted by this flow, for which the function is defined, and emits the result.
    * If `f` is not defined at an element, the element will be skipped.
    *
    * @param f
    *   The mapping function.
    */
  def collect[U](f: PartialFunction[T, U]): Flow[U] =
    addTransformSinkStage(next => FlowSink.propagateClose(next)(t => if f.isDefinedAt(t) then next.onNext(f(t))))

  def tap(f: T => Unit): Flow[T] = map(t =>
    f(t); t
  )

  def intersperse[U >: T](inject: U): Flow[U] = intersperse(None, inject, None)

  /** Intersperses elements emitted by this flow with `inject` elements. The `start` element is emitted at the beginning; `end` is emitted
    * after the current flow emits the last element.
    *
    * @param start
    *   An element to be emitted at the beginning.
    * @param inject
    *   An element to be injected between the flow elements.
    * @param end
    *   An element to be emitted at the end.
    */
  def intersperse[U >: T](start: U, inject: U, end: U): Flow[U] = intersperse(Some(start), inject, Some(end))

  private def intersperse[U >: T](start: Option[U], inject: U, end: Option[U]): Flow[U] = Flow(
    new FlowStage:
      override def run(sink: FlowSink[U]): Unit =
        start.foreach(sink.onNext)
        last.run(
          new FlowSink[U]:
            private var firstEmitted = false
            override def onNext(t: U): Unit =
              if firstEmitted then sink.onNext(inject)
              sink.onNext(t)
              firstEmitted = true
            override def onDone(): Unit =
              end.foreach(sink.onNext)
              sink.onDone()
            override def onError(e: Throwable): Unit =
              sink.onError(e)
        )
      end run
  )

  /** Applies the given mapping function `f` to each element emitted by this flow. At most `parallelism` invocations of `f` are run in
    * parallel.
    *
    * The mapped results are emitted in the same order, in which inputs are received. In other words, ordering is preserved.
    *
    * @param parallelism
    *   An upper bound on the number of forks that run in parallel. Each fork runs the function `f` on a single element from the flow.
    * @param f
    *   The mapping function.
    */
  def mapPar[U](parallelism: Int)(f: T => U): Flow[U] = Flow(
    new FlowStage:
      override def run(sink: FlowSink[U]): Unit = mapParScope(parallelism, f, sink)
  )

  // using a separate function so that we don't accidentaly use the `mainScope` instance when creating forks
  private def mapParScope[U](parallelism: Int, f: T => U, sink: FlowSink[U]) =
    val s = new Semaphore(parallelism)
    val inProgress = Channel.withCapacity[Fork[Option[U]]](parallelism)
    val results = Channel.withCapacity[U](parallelism)

    def forkMapping(t: T)(using OxUnsupervised): Fork[Option[U]] =
      forkUnsupervised {
        try
          val u = f(t)
          s.release() // not in finally, as in case of an exception, no point in starting subsequent forks
          Some(u)
        catch
          case t: Throwable => // same as in `forkPropagate`, catching all exceptions
            results.errorOrClosed(t)
            None
      }

    // creating a nested scope, so that in case of errors, we can clean up any mapping forks in a "local" fashion,
    // that is without closing the main scope; any error management must be done in the forks, as the scope is
    // unsupervised
    unsupervised:
      // a fork which runs the `last` pipeline, and for each emitted element creates a fork
      forkPropagate(results):
        last.run(new FlowSink[T]:
          override def onNext(t: T): Unit =
            s.acquire()
            inProgress.sendOrClosed(forkMapping(t)).discard

          override def onDone(): Unit = inProgress.doneOrClosed().discard

          override def onError(e: Throwable): Unit =
            // notifying only the `results` channels, as it will cause the scope to end, and any other forks to be
            // interrupted, including the inProgress-fork, which might be waiting on a join()
            results.errorOrClosed(e).discard
        )

      // a fork in which we wait for the created forks to finish (in sequence), and forward the mapped values to `results`
      // this extra step is needed so that if there's an error in any of the mapping forks, it's discovered as quickly as
      // possible in the main body
      forkUnsupervised:
        repeatWhile:
          inProgress.receiveOrClosed() match
            // in the fork's result is a `None`, the error is already propagated to the `results` channel
            case f: Fork[Option[U]] @unchecked => f.join().map(results.sendOrClosed).isDefined
            case ChannelClosed.Done            => results.done(); false
            case ChannelClosed.Error(e)        => throw new IllegalStateException("inProgress should never be closed with an error", e)

      // in the main body, we call the `sink` methods using the (sequentially received) results; when an error occurs,
      // the scope ends, interrupting any forks that are still running
      repeatWhile:
        results.receiveOrClosed() match
          case ChannelClosed.Done     => sink.onDone(); false
          case ChannelClosed.Error(e) => sink.onError(e); false
          case u: U @unchecked        => sink.onNext(u); true
  end mapParScope

  def mapParUnordered[U](parallelism: Int)(f: T => U): Flow[U] = Flow(
    new FlowStage:
      override def run(sink: FlowSink[U]): Unit =
        val results = Channel.buffered[U](parallelism)
        val s = new Semaphore(parallelism)
        unsupervised: // the outer scope, used for the fork which runs the `last` pipeline
          forkPropagate(results):
            supervised: // the inner scope, in which user forks are created, and which is used to wait for all to complete when done
              last.run(new FlowSink[T]:
                override def onNext(t: T): Unit =
                  s.acquire()
                  forkUserPropagate(results):
                    results.sendOrClosed(f(t))
                    s.release()
                  .discard
                end onNext
                override def onDone(): Unit = () // only completing `results` once all forks finish
                override def onError(e: Throwable): Unit = results.errorOrClosed(e).discard
              )
            results.doneOrClosed().discard

          repeatWhile:
            results.receiveOrClosed() match
              case ChannelClosed.Done     => sink.onDone(); false
              case ChannelClosed.Error(e) => sink.onError(e); false
              case u: U @unchecked        => sink.onNext(u); true
      end run
  )

  private val abortTake = new Exception("abort take")

  def take(n: Int): Flow[T] = Flow(
    new FlowStage:
      override def run(sink: FlowSink[T]): Unit =
        var taken = 0
        try
          last.run(new FlowSink[T]:
            override def onNext(t: T): Unit =
              if taken < n then
                sink.onNext(t)
                taken += 1

              if taken == n then throw abortTake
            override def onDone(): Unit = sink.onDone()
            override def onError(e: Throwable): Unit = sink.onError(e)
          )
        catch case `abortTake` => sink.onDone()
        end try
      end run
  )

  /** Transform the flow so that it emits elements as long as predicate `f` is satisfied (returns `true`). If `includeFirstFailing` is
    * `true`, the flow will additionally emit the first element that failed the predicate. After that, the flow will complete as done.
    *
    * @param f
    *   A predicate function called on incoming elements.
    * @param includeFirstFailing
    *   Whether the flow should also emit the first element that failed the predicate (`false` by default).
    */
  def takeWhile(f: T => Boolean, includeFirstFailing: Boolean = false): Flow[T] = Flow(
    new FlowStage:
      override def run(sink: FlowSink[T]): Unit =
        try
          last.run(new FlowSink[T]:
            override def onNext(t: T): Unit =
              if f(t) then sink.onNext(t)
              else
                if includeFirstFailing then sink.onNext(t)
                throw abortTake
            override def onDone(): Unit = sink.onDone()
            override def onError(e: Throwable): Unit = sink.onError(e)
          )
        catch case `abortTake` => sink.onDone()
        end try
      end run
  )

  /** Drops `n` elements from this flow and emits subsequent elements.
    *
    * @param n
    *   Number of elements to be dropped.
    */
  def drop(n: Int): Flow[T] = Flow(
    new FlowStage:
      override def run(sink: FlowSink[T]): Unit =
        var dropped = 0
        last.run(new FlowSink[T]:
          override def onNext(t: T): Unit =
            if dropped < n then dropped += 1
            else sink.onNext(t)
          override def onDone(): Unit = sink.onDone()
          override def onError(e: Throwable): Unit = sink.onError(e)
        )
      end run
  )

  //

  private inline def addTransformSinkStage[U](inline doTransform: FlowSink[U] => FlowSink[T]): Flow[U] =
    Flow(
      new FlowStage:
        override def run(next: FlowSink[U]): Unit =
          last.run(doTransform(next))
    )
end FlowOps
