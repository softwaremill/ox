package ox.flow

import ox.Fork
import ox.channels.ChannelClosed
import ox.channels.ChannelClosedUnion.isValue
import ox.channels.Source
import ox.channels.StageCapacity
import ox.forever
import ox.forkUnsupervised
import ox.repeatWhile
import ox.sleep
import ox.unsupervised

import scala.collection.mutable
import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.duration.Duration
import scala.concurrent.duration.FiniteDuration

trait FlowCompanionOps:
  this: Flow.type =>

  private[flow] inline def usingSinkInline[T](inline withSink: FlowSink[T] => Unit): Flow[T] = Flow(
    new FlowStage:
      override def run(sink: FlowSink[T]): Unit = withSink(sink)
  )

  def usingSink[T](withSink: FlowSink[T] => Unit): Flow[T] = usingSinkInline(withSink)

  def fromSource[T](source: Source[T]): Flow[T] = Flow(FlowStage.FromSource(source))

  def fromIterable[T](it: Iterable[T]): Flow[T] = fromIterator(it.iterator)

  def fromValues[T](ts: T*): Flow[T] = fromIterator(ts.iterator)

  def fromIterator[T](it: => Iterator[T]): Flow[T] = usingSinkInline: sink =>
    val theIt = it
    while theIt.hasNext do sink(theIt.next())

  def fromFork[T](f: Fork[T]): Flow[T] = usingSinkInline: sink =>
    sink(f.join())

  def iterate[T](zero: T)(f: T => T): Flow[T] = usingSinkInline: sink =>
    var t = zero
    forever:
      sink(t)
      t = f(t)

  /** A range of numbers, from `from`, to `to` (inclusive), stepped by `step`. */
  def range(from: Int, to: Int, step: Int): Flow[Int] = usingSinkInline: sink =>
    var t = from
    repeatWhile:
      sink(t)
      t = t + step
      t <= to

  def unfold[S, T](initial: S)(f: S => Option[(T, S)]): Flow[T] = usingSinkInline: sink =>
    var s = initial
    repeatWhile:
      f(s) match
        case Some((value, next)) =>
          sink(value)
          s = next
          true
        case None => false

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
  def tick[T](interval: FiniteDuration, value: T = ()): Flow[T] = usingSinkInline: sink =>
    forever:
      val start = System.nanoTime()
      sink(value)
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
  def repeatEval[T](f: => T): Flow[T] = usingSinkInline: sink =>
    forever:
      sink(f)

  /** Creates a flow, which emits the value contained in the result of evaluating `f` repeatedly. When the evaluation of `f` returns a
    * `None`, the flow is completed as "done", and no more values are evaluated or emitted.
    *
    * As the `f` parameter is passed by-name, the evaluation is deferred until the element is emitted, and happens multiple times.
    *
    * @param f
    *   The code block, computing the optional element to emit.
    */
  def repeatEvalWhileDefined[T](f: => Option[T]): Flow[T] = usingSinkInline: sink =>
    repeatWhile:
      f match
        case Some(value) => sink.apply(value); true
        case None        => false

  /** A flow which sleeps for the given `timeout` and then completes as done. */
  def timeout[T](timeout: FiniteDuration): Flow[T] = usingSinkInline: sink =>
    sleep(timeout)

  // TODO: concat failed + values -> will it continue?

  def concat[T](flows: Seq[Flow[T]]): Flow[T] = usingSinkInline: sink =>
    flows.iterator.foreach: currentFlow =>
      currentFlow.runToSink(FlowSink.fromInline(sink.apply))

  def empty[T]: Flow[T] = Flow(
    new FlowStage:
      override def run(sink: FlowSink[T]): Unit = () // done
  )

  /** Creates a flow that emits a single element when `from` completes. */
  def fromFuture[T](from: Future[T]): Flow[T] = usingSinkInline: sink =>
    sink(Await.result(from, Duration.Inf))

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
      override def run(sink: FlowSink[T]): Unit = throw t
  )

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
        usingSinkInline: sink =>
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
