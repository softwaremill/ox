package ox.flow

import ox.channels.Source
import ox.Ox
import ox.Fork
import ox.forever
import scala.concurrent.duration.FiniteDuration
import ox.sleep
import scala.concurrent.Future
import scala.concurrent.Await
import scala.concurrent.duration.Duration
import ox.repeatWhile

trait FlowCompanionOps { this: Flow.type =>
  def fromSource[T](source: Source[T]): Flow[T] = Flow(FlowStage.fromSource(source))

  def fromSender[T](withSender: FlowSender[T] => Unit): Flow[T] = Flow(
    new FlowStage[T]:
      def run(sink: FlowSink[T])(using Ox): Unit =
        withSender(
          new FlowSender[T]:
            def apply(t: T): Unit = sink.onNext(t)
        )
        sink.onDone()
  )

  def fromIterable[T](it: Iterable[T]): Flow[T] = fromIterator(it.iterator)

  def fromValues[T](ts: T*): Flow[T] = fromIterator(ts.iterator)

  def fromIterator[T](it: => Iterator[T]): Flow[T] =
    Flow(
      new FlowStage:
        override def run(sink: FlowSink[T])(using Ox): Unit =
          val theIt = it
          while theIt.hasNext do sink.onNext(theIt.next())
          sink.onDone()
    )

  def fromFork[T](f: Fork[T]): Flow[T] = Flow(
    new FlowStage:
      override def run(sink: FlowSink[T])(using Ox): Unit =
        sink.onNext(f.join())
        sink.onDone()
  )

  def iterate[T](zero: T)(f: T => T): Flow[T] = Flow(
    new FlowStage:
      override def run(sink: FlowSink[T])(using Ox): Unit =
        var t = zero
        forever:
          sink.onNext(t)
          t = f(t)
  )

  /** A range of numbers, from `from`, to `to` (inclusive), stepped by `step`. */
  def range(from: Int, to: Int, step: Int): Flow[Int] = Flow(
    new FlowStage:
      override def run(sink: FlowSink[Int])(using Ox): Unit =
        var t = from
        repeatWhile:
          sink.onNext(t)
          t = t + step
          t <= to
        sink.onDone()
  )

  def unfold[S, T](initial: S)(f: S => Option[(T, S)]): Flow[T] = Flow(
    new FlowStage:
      override def run(sink: FlowSink[T])(using Ox): Unit =
        var s = initial
        repeatWhile:
          f(s) match
            case Some((value, next)) =>
              sink.onNext(value)
              s = next
              true
            case None =>
              sink.onDone()
              false
  )

  /** Creates a flow which emits the given `value` repeatedly, at least [[interval]] apart between each two elements. The first value is
    * emitted immediately.
    *
    * The interval is measured between subsequent emissions. Hence, if the following transformation pipeline is slow, the next emission can
    * occur immediately after the previous one is fully processed (if processing took more than the inter-emission interval duration).
    * However, ticks do not accumulate; for example, if processing is slow enough that multiple intervals pass between send invocations,
    * only one tick will be sent.
    *
    * @param interval
    *   The temporal spacing between subsequent ticks.
    * @param value
    *   The element to emitted on every tick.
    * @example
    *   {{{
    *   scala>
    *   import ox.*
    *   import ox.flow.Flow
    *   import scala.concurrent.duration.DurationInt
    *
    *   supervised {
    *     val s = Flow.tick(100.millis).runToChannel()
    *     s.receive()
    *     s.receive() // this will complete at least 100 milliseconds later
    *   }
    *   }}}
    */
  def tick[T](interval: FiniteDuration, value: T = ())(using Ox): Flow[T] = Flow(
    new FlowStage:
      override def run(sink: FlowSink[T])(using Ox): Unit =
        forever:
          val start = System.nanoTime()
          sink.onNext(value)
          val end = System.nanoTime()
          val sleep = interval.toNanos - (end - start)
          if sleep > 0 then Thread.sleep(sleep / 1_000_000, (sleep % 1_000_000).toInt)
  )

  /** Creates a flow, which emits the given `element` repeatedly. */
  def repeat[T](element: T = ()): Flow[T] = repeatEval(element)

  /** Creates a flow, which emits the result of evaluating `f` repeatedly. As the parameter is passed by-name, the evaluation is deferred
    * until the element is emitted, and happens multiple times.
    *
    * @param f
    *   The code block, computing the element to emit.
    */
  def repeatEval[T](f: => T): Flow[T] = Flow(
    new FlowStage:
      override def run(sink: FlowSink[T])(using Ox): Unit =
        forever:
          sink.onNext(f)
  )

  /** Creates a flow, which emits the value contained in the result of evaluating `f` repeatedly. When the evaluation of `f` returns a
    * `None`, the flow is completed as "done", and no more values are evaluated or emitted.
    *
    * As the `f` parameter is passed by-name, the evaluation is deferred until the element is emitted, and happens multiple times.
    *
    * @param f
    *   The code block, computing the optional element to emit.
    */
  def repeatEvalWhileDefined[T](f: => Option[T]): Flow[T] = Flow(
    new FlowStage:
      override def run(sink: FlowSink[T])(using Ox): Unit =
        repeatWhile:
          f match
            case Some(value) => sink.onNext(value); true
            case None        => sink.onDone(); false
  )

  def timeout[T](timeout: FiniteDuration, element: T = ()): Flow[T] = Flow(
    new FlowStage:
      override def run(sink: FlowSink[T])(using Ox): Unit =
        sleep(timeout)
        sink.onNext(element)
        sink.onDone()
  )

  def concat[T](flows: Seq[Flow[T]]): Flow[T] = Flow(
    new FlowStage:
      override def run(sink: FlowSink[T])(using Ox): Unit =
        flows.iterator.foreach: currentFlow =>
          currentFlow.runWithFlowSink(
            new FlowSink[T]:
              override def onNext(t: T): Unit = sink.onNext(t)
              override def onDone(): Unit = ()
              override def onError(e: Throwable): Unit = sink.onError(e)
          )
        sink.onDone()
  )

  def empty[T]: Flow[T] = Flow(
    new FlowStage:
      override def run(sink: FlowSink[T])(using Ox): Unit =
        sink.onDone()
  )

  /** Creates a flow that emits a single element when `from` completes. */
  def fromFuture[T](from: Future[T]): Flow[T] = Flow(
    new FlowStage:
      override def run(sink: FlowSink[T])(using Ox): Unit =
        sink.onNext(Await.result(from, Duration.Inf))
        sink.onDone()
  )

  /** Creates a flow that emits all elements from the given future source when `from` completes. */
  def fromFutureSource[T](from: Future[Source[T]]): Flow[T] =
    fromSource(Await.result(from, Duration.Inf))

  /** Creates a flow that fails immediately with the given [[java.lang.Throwable]]
    *
    * @param t
    *   The [[java.lang.Throwable]] to fail with
    */
  def failed[T](t: Throwable): Flow[T] = Flow(
    new FlowStage:
      override def run(sink: FlowSink[T])(using Ox): Unit =
        sink.onError(t)
  )
}

// a simplified sink used in .fromSender
trait FlowSender[T]:
  def apply(t: T): Unit
