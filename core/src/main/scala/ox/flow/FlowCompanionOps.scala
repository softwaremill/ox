package ox.flow

import ox.Fork
import ox.channels.ChannelClosed
import ox.channels.ChannelClosedUnion.isValue
import ox.channels.Source
import ox.channels.BufferCapacity
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
import scala.annotation.nowarn

trait FlowCompanionOps:
  this: Flow.type =>

  // suppressing the "New anonymous class definition will be duplicated at each inline site" warning: the whole point of this inline
  // is to create new FlowStage instances
  @nowarn private[flow] inline def usingEmitInline[T](inline withEmit: FlowEmit[T] => Unit): Flow[T] = Flow(
    new FlowStage:
      override def run(emit: FlowEmit[T]): Unit = withEmit(emit)
  )

  /** Creates a flow, which when run, provides a [[FlowEmit]] instance to the given `withEmit` function. Elements can be emitted to be
    * processed by downstream stages by calling [[FlowEmit.apply]].
    *
    * The `FlowEmit` instance provided to the `withEmit` callback should only be used on the calling thread. That is, `FlowEmit` is
    * thread-unsafe. Moreover, the instance should not be stored or captured in closures, which outlive the invocation of `withEmit`.
    */
  def usingEmit[T](withEmit: FlowEmit[T] => Unit): Flow[T] = usingEmitInline(withEmit)

  /** Creates a flow using the given `source`. An element is emitted for each value received from the source. If the source is completed
    * with an error, is it propagated by throwing.
    */
  def fromSource[T](source: Source[T]): Flow[T] = Flow(FlowStage.FromSource(source))

  /** Creates a flow from the given `iterable`. Each element of the iterable is emitted in order. */
  def fromIterable[T](it: Iterable[T]): Flow[T] = fromIterator(it.iterator)

  /** Creates a flow from the given values. Each value is emitted in order. */
  def fromValues[T](ts: T*): Flow[T] = fromIterator(ts.iterator)

  /** Creates a flow from the given (lazily evaluated) `iterator`. Each element of the iterator is emitted in order. */
  def fromIterator[T](it: => Iterator[T]): Flow[T] = usingEmitInline: emit =>
    val theIt = it
    while theIt.hasNext do emit(theIt.next())

  /** Creates a flow from the given fork. The flow will emit up to one element, or complete by throwing an exception if the fork fails. */
  def fromFork[T](f: Fork[T]): Flow[T] = usingEmitInline: emit =>
    emit(f.join())

  /** Creates a flow which emits elements starting with `zero`, and then applying `f` to the previous element to get the next one. */
  def iterate[T](zero: T)(f: T => T): Flow[T] = usingEmitInline: emit =>
    var t = zero
    forever:
      emit(t)
      t = f(t)

  /** Creates a flow which emits a range of numbers, from `from`, to `to` (inclusive), stepped by `step`. */
  def range(from: Int, to: Int, step: Int): Flow[Int] = usingEmitInline: emit =>
    var t = from
    repeatWhile:
      emit(t)
      t = t + step
      t <= to

  /** Creates a flow which emits the first element of tuples returned by repeated applications of `f`. The `initial` state is used for the
    * first application, and then the state is updated with the second element of the tuple. Emission stops when `f` returns `None`,
    * otherwise it continues indefinitely.
    */
  def unfold[S, T](initial: S)(f: S => Option[(T, S)]): Flow[T] = usingEmitInline: emit =>
    var s = initial
    repeatWhile:
      f(s) match
        case Some((value, next)) =>
          emit(value)
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
  def tick[T](interval: FiniteDuration, value: T = ()): Flow[T] = usingEmitInline: emit =>
    forever:
      val start = System.nanoTime()
      emit(value)
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
  def repeatEval[T](f: => T): Flow[T] = usingEmitInline: emit =>
    forever:
      emit(f)

  /** Creates a flow, which emits the value contained in the result of evaluating `f` repeatedly. When the evaluation of `f` returns a
    * `None`, the flow is completed as "done", and no more values are evaluated or emitted.
    *
    * As the `f` parameter is passed by-name, the evaluation is deferred until the element is emitted, and happens multiple times.
    *
    * @param f
    *   The code block, computing the optional element to emit.
    */
  def repeatEvalWhileDefined[T](f: => Option[T]): Flow[T] = usingEmitInline: emit =>
    repeatWhile:
      f match
        case Some(value) => emit.apply(value); true
        case None        => false

  /** Create a flow which sleeps for the given `timeout` and then completes as done. */
  def timeout[T](timeout: FiniteDuration): Flow[T] = usingEmitInline: emit =>
    sleep(timeout)

  /** Creates a flow which concatenates the given `flows` in order. First elements from the first flow are emitted, then from the second
    * etc. If any of the flows compeltes with an error, is is propagated.
    */
  def concat[T](flows: Seq[Flow[T]]): Flow[T] = usingEmitInline: emit =>
    flows.iterator.foreach: currentFlow =>
      currentFlow.runToEmit(FlowEmit.fromInline(emit.apply))

  /** Creates an empty flow, which emits no elements and completes immediately. */
  def empty[T]: Flow[T] = Flow(
    new FlowStage:
      override def run(emit: FlowEmit[T]): Unit = () // done
  )

  /** Creates a flow that emits a single element when `from` completes, or throws an exception when `from` fails. */
  def fromFuture[T](from: Future[T]): Flow[T] = usingEmitInline: emit =>
    emit(Await.result(from, Duration.Inf))

  /** Creates a flow that emits all elements from the given future [[Source]] when `from` completes. */
  def fromFutureSource[T](from: Future[Source[T]]): Flow[T] =
    fromSource(Await.result(from, Duration.Inf))

  /** Creates a flow that fails immediately with the given [[java.lang.Throwable]]
    *
    * @param t
    *   The [[java.lang.Throwable]] to fail with
    */
  def failed[T](t: Throwable): Flow[T] = Flow(
    new FlowStage:
      override def run(emit: FlowEmit[T]): Unit = throw t
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
  def interleaveAll[T](flows: Seq[Flow[T]], segmentSize: Int = 1, eagerComplete: Boolean = false)(using BufferCapacity): Flow[T] =
    flows match
      case Nil           => Flow.empty
      case single :: Nil => single
      case _ =>
        usingEmitInline: emit =>
          val results = BufferCapacity.newChannel[T]
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
            FlowEmit.channelToEmit(results, emit)

end FlowCompanionOps
