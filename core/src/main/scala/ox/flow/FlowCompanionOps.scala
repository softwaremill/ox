package ox.flow

import ox.channels.Source
import ox.Fork
import ox.forever
import scala.concurrent.duration.FiniteDuration
import ox.sleep
import scala.concurrent.Future
import scala.concurrent.Await
import scala.concurrent.duration.Duration
import scala.collection.mutable
import ox.repeatWhile
import ox.channels.StageCapacity
import ox.unsupervised
import ox.channels.ChannelClosed
import ox.forkUnsupervised
import ox.channels.ChannelClosedUnion.isValue

trait FlowCompanionOps:
  this: Flow.type =>

  private[flow] inline def fromSink[T](inline runWithSink: FlowSink[T] => Unit): Flow[T] = Flow(
    new FlowStage:
      override def run(sink: FlowSink[T]): Unit = runWithSink(sink)
  )

  // TODO: by-name?
  // TODO optimizing fromSource + toChannel ?
  def fromSource[T](source: Source[T]): Flow[T] = Flow(FlowStage.fromSource(source))

  def fromSender[T](withSender: FlowSender[T] => Unit): Flow[T] = fromSink: sink =>
    withSender(
      new FlowSender[T]:
        def apply(t: T): Unit = sink.onNext(t)
    )
    sink.onDone()

  def fromIterable[T](it: Iterable[T]): Flow[T] = fromIterator(it.iterator)

  def fromValues[T](ts: T*): Flow[T] = fromIterator(ts.iterator)

  def fromIterator[T](it: => Iterator[T]): Flow[T] = fromSink: sink =>
    val theIt = it
    while theIt.hasNext do sink.onNext(theIt.next())
    sink.onDone()

  def fromFork[T](f: Fork[T]): Flow[T] = fromSink: sink =>
    sink.onNext(f.join())
    sink.onDone()

  def iterate[T](zero: T)(f: T => T): Flow[T] = fromSink: sink =>
    var t = zero
    forever:
      sink.onNext(t)
      t = f(t)

  /** A range of numbers, from `from`, to `to` (inclusive), stepped by `step`. */
  def range(from: Int, to: Int, step: Int): Flow[Int] = fromSink: sink =>
    var t = from
    repeatWhile:
      sink.onNext(t)
      t = t + step
      t <= to
    sink.onDone()

  def unfold[S, T](initial: S)(f: S => Option[(T, S)]): Flow[T] = fromSink: sink =>
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
    */
  def tick[T](interval: FiniteDuration, value: T = ()): Flow[T] = fromSink: sink =>
    forever:
      val start = System.nanoTime()
      sink.onNext(value)
      val end = System.nanoTime()
      val sleep = interval.toNanos - (end - start)
      if sleep > 0 then Thread.sleep(sleep / 1_000_000, (sleep % 1_000_000).toInt)

  /** Creates a flow, which emits the given `element` repeatedly. */
  def repeat[T](element: T = ()): Flow[T] = repeatEval(element)

  /** Creates a flow, which emits the result of evaluating `f` repeatedly. As the parameter is passed by-name, the evaluation is deferred
    * until the element is emitted, and happens multiple times.
    *
    * @param f
    *   The code block, computing the element to emit.
    */
  def repeatEval[T](f: => T): Flow[T] = fromSink: sink =>
    forever:
      sink.onNext(f)

  /** Creates a flow, which emits the value contained in the result of evaluating `f` repeatedly. When the evaluation of `f` returns a
    * `None`, the flow is completed as "done", and no more values are evaluated or emitted.
    *
    * As the `f` parameter is passed by-name, the evaluation is deferred until the element is emitted, and happens multiple times.
    *
    * @param f
    *   The code block, computing the optional element to emit.
    */
  def repeatEvalWhileDefined[T](f: => Option[T]): Flow[T] = fromSink: sink =>
    repeatWhile:
      f match
        case Some(value) => sink.onNext(value); true
        case None        => sink.onDone(); false

  /** A flow which sleeps for the given `timeout` and then completes as done. */
  def timeout[T](timeout: FiniteDuration): Flow[T] = fromSink: sink =>
    sleep(timeout)
    sink.onDone()

  // TODO: concat failed + values -> will it continue?

  def concat[T](flows: Seq[Flow[T]]): Flow[T] = fromSink: sink =>
    flows.iterator.foreach: currentFlow =>
      currentFlow.runWithFlowSink(
        new FlowSink[T]:
          override def onNext(t: T): Unit = sink.onNext(t)
          override def onDone(): Unit = ()
          override def onError(e: Throwable): Unit = sink.onError(e)
      )
    sink.onDone()

  def empty[T]: Flow[T] = fromSink: sink =>
    sink.onDone()

  /** Creates a flow that emits a single element when `from` completes. */
  def fromFuture[T](from: Future[T]): Flow[T] = fromSink: sink =>
    sink.onNext(Await.result(from, Duration.Inf))
    sink.onDone()

  /** Creates a flow that emits all elements from the given future source when `from` completes. */
  def fromFutureSource[T](from: Future[Source[T]]): Flow[T] =
    fromSource(Await.result(from, Duration.Inf))

  /** Creates a flow that fails immediately with the given [[java.lang.Throwable]]
    *
    * @param t
    *   The [[java.lang.Throwable]] to fail with
    */
  def failed[T](t: Throwable): Flow[T] = fromSink: sink =>
    sink.onError(t)

  /** Sends a given number of elements (determined byc `segmentSize`) from each flow in `flows` to the returned flow and repeats. The order
    * of elements in all flows is preserved.
    *
    * If any of the flows is done before the others, the behavior depends on the `eagerCancel` flag. When set to `true`, the returned flow
    * is completed immediately, otherwise the interleaving continues with the remaining non-completed flows. Once all but one flows are
    * complete, the elements of the remaining non-complete flow are emitted by the returned flow.
    *
    * The provided flows are run concurrently and asynchronously.
    *
    * @param flows
    *   The flows whose elements will be interleaved.
    * @param segmentSize
    *   The number of elements sent from each flow before switching to the next one. Default is 1.
    * @param eagerComplete
    *   If `true`, the returned flow is completed as soon as any of the flows completes. If `false`, the interleaving continues with the
    *   remaining non-completed flows.
    */
  def interleaveAll[T](flows: Seq[Flow[T]], segmentSize: Int = 1, eagerComplete: Boolean = false)(using StageCapacity): Flow[T] =
    flows match
      case Nil           => Flow.empty
      case single :: Nil => single
      case _ =>
        fromSink: sink =>
          val results = StageCapacity.newChannel[T]
          unsupervised:
            forkUnsupervised:
              val availableSources = mutable.ArrayBuffer.from(flows.map(_.runToChannel()))
              var currentSourceIndex = 0
              var elementsRead = 0

              def completeCurrentSource(): Unit =
                availableSources.remove(currentSourceIndex)
                currentSourceIndex = if currentSourceIndex == 0 then availableSources.size - 1 else currentSourceIndex - 1

              def switchToNextSource(): Unit =
                currentSourceIndex = (currentSourceIndex + 1) % availableSources.size
                elementsRead = 0

              repeatWhile:
                availableSources(currentSourceIndex).receiveOrClosed() match
                  case ChannelClosed.Done =>
                    completeCurrentSource()

                    if eagerComplete || availableSources.isEmpty then
                      results.doneOrClosed()
                      false
                    else
                      switchToNextSource()
                      true
                  case ChannelClosed.Error(r) =>
                    results.errorOrClosed(r)
                    false
                  case value: T @unchecked =>
                    elementsRead += 1
                    // after reaching segmentSize, only switch to next source if there's any other available
                    if elementsRead == segmentSize && availableSources.size > 1 then switchToNextSource()
                    results.sendOrClosed(value).isValue
            FlowSink.channelToSink(results, sink)

end FlowCompanionOps

// a simplified sink used in .fromSender
trait FlowSender[T]:
  def apply(t: T): Unit
