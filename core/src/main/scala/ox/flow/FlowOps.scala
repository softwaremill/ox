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

class FlowOps[+T]:
  outer: Flow[T] =>

  def map[U](f: T => U): Flow[U] = addTransformSinkStage(next => FlowSink.propagateClose(next)(t => next.onNext(f(t))))

  def filter(f: T => Boolean): Flow[T] = addTransformSinkStage(next => FlowSink.propagateClose(next)(t => if f(t) then next.onNext(t)))

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
      override def run(sink: FlowSink[U])(using Ox): Unit =
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
    *   An upper bound on the number of forks that run in parallel. Each fork runs the function `f` on a single element from the flow.
    * @param f
    *   The mapping function.
    */
  def mapPar[U](parallelism: Int)(f: T => U): Flow[U] = Flow(
    new FlowStage:
      override def run(sink: FlowSink[U])(using Ox): Unit = mapParScope(parallelism, f, sink, summon[Ox])
  )

  private def mapParScope[U](parallelism: Int, f: T => U, sink: FlowSink[U], mainScope: Ox) =
    val s = new Semaphore(parallelism)
    val inProgress = Channel.withCapacity[Fork[Option[U]]](parallelism)
    val results = Channel.withCapacity[U](parallelism)
    // creating a nested scope, so that in case of errors, we can clean up any mapping forks in a "local" fashion,
    // that is without closing the main scope; any error management must be done in the forks, as the scope is
    // unsupervised
    unsupervised {
      // a fork which runs the `last` pipeline, and for each emitted element creates a fork
      forkUnsupervised:
        try
          last.run(new FlowSink[T]:
            override def onNext(t: T): Unit =
              s.acquire()
              inProgress
                .sendOrClosed(forkUnsupervised {
                  try
                    val u = f(t)
                    s.release() // not in finally, as in case of an exception, no point in starting subsequent forks
                    Some(u)
                  catch
                    case t: Throwable => // same as in `forkPropagate`, catching all exceptions
                      results.errorOrClosed(t)
                      None
                })
                .discard
            end onNext

            override def onDone(): Unit = inProgress.done()

            override def onError(e: Throwable): Unit =
              // notifying only the `results` channels, as it will cause the scope to end, and any other forks to be
              // interrupted, including the inProgress-fork, which might be waiting on a join()
              results.error(e)
          )(using mainScope) // using the main scope to run the pipeline - any unhandled errors there will close it
        catch
          case e: Throwable =>
            results.errorOrClosed(e)

      // a fork in which we wait for the created forks to finish (in sequence), and forward the mapped values to `results`
      // this extra step is needed so that if there's an error in any of the mapping forks, it's discovered as quickly as possible
      forkUnsupervised {
        repeatWhile {
          inProgress.receiveOrClosed() match
            // in the fork's result is a `None`, the error is already propagated to the `results` channel
            case f: Fork[Option[U]] @unchecked => f.join().map(results.sendOrClosed).isDefined
            case ChannelClosed.Done            => results.done(); false
            case ChannelClosed.Error(e)        => throw new IllegalStateException("inProgress should never be closed with an error", e)
        }
      }

      // in the main thread, we call the `sink` methods using the (sequentially received) results; when an error occurs,
      // the scope ends, interrupting any forks that are still running
      repeatWhile {
        results.receiveOrClosed() match
          case ChannelClosed.Done     => sink.onDone(); false
          case ChannelClosed.Error(e) => sink.onError(e); false
          case u: U @unchecked        => sink.onNext(u); true
      }
    }
  end mapParScope

  //

  private inline def addTransformSinkStage[U](inline doTransform: FlowSink[U] => FlowSink[T]): Flow[U] =
    Flow(
      new FlowStage:
        override def run(next: FlowSink[U])(using Ox): Unit =
          last.run(doTransform(next))
    )
end FlowOps
