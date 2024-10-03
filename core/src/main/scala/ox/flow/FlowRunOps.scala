package ox.flow

import ox.channels.StageCapacity
import ox.channels.Source
import ox.channels.Sink
import ox.discard
import scala.collection.mutable.ListBuffer
import ox.OxUnsupervised

trait FlowRunOps[+T]:
  this: Flow[T] =>

  /** Invokes the given function for each emitted element. Blocks until the flow completes. */
  def runForeach(sink: T => Unit): Unit =
    last.run(
      new FlowSink[T]:
        override def apply(t: T): Unit = sink(t)
    )

  def runToSink(sink: FlowSink[T]): Unit = last.run(sink)

  def runToChannel()(using OxUnsupervised, StageCapacity): Source[T] =
    val ch = StageCapacity.newChannel[T]
    runLastToChannelAsync(ch)
    ch

  /** Accumulates all elements emitted by this flow into a list. Blocks until the flow completes. */
  def runToList(): List[T] =
    val b = List.newBuilder[T]
    runForeach(b += _)
    b.result()

  /** Passes each element emitted by this flow to the given sink. Blocks until the flow completes.
    *
    * Errors are always propagated. Successful flow completion is propagated when `propagateDone` is set to `true`.
    */
  def runPipeToSink(sink: Sink[T], propagateDone: Boolean): Unit =
    last.run(
      new FlowSink[T]:
        override def apply(t: T): Unit = sink.send(t)
    )
    if propagateDone then sink.doneOrClosed().discard

  /** Ignores all elements emitted by the flow. Blocks until the flow completes. */
  def runDrain(): Unit = runForeach(_ => ())

  /** Returns the last element emitted by this flow, wrapped in [[Some]], or [[None]] when this source is empty. */
  def runLastOption(): Option[T] =
    var value: Option[T] = None
    last.run(
      new FlowSink[T]:
        override def apply(t: T): Unit = value = Some(t)
    )
    value
  end runLastOption

  /** Returns the last element emitted by this flow, or throws [[NoSuchElementException]] when the flow emits no elements (is empty).
    *
    * @throws NoSuchElementException
    *   When this flow is empty.
    */
  def runLast(): T = runLastOption().getOrElse(throw new NoSuchElementException("cannot obtain last element from an empty source"))

  /** Uses `zero` as the current value and applies function `f` on it and a value emitted by this flow. The returned value is used as the
    * next current value and `f` is applied again with the next value emitted by the flow. The operation is repeated until the flow emits
    * all elements.
    *
    * @param zero
    *   An initial value to be used as the first argument to function `f` call.
    * @param f
    *   A binary function (a function that takes two arguments) that is applied to the current value and value emitted by the flow.
    * @return
    *   Combined value retrieved from running function `f` on all flow elements in a cumulative manner where result of the previous call is
    *   used as an input value to the next.
    */
  def runFold[U](zero: U)(f: (U, T) => U): U =
    var current = zero
    last.run(
      new FlowSink[T]:
        override def apply(t: T): Unit = current = f(current, t)
    )
    current
  end runFold

  /** Applies function `f` on the first and the following (if available) elements emitted by this flow. The returned value is used as the
    * next current value and `f` is applied again with the next value emitted by this source. The operation is repeated until this flow
    * emits all elements. This is similar operation to [[fold]] but it uses the first emitted element as `zero`.
    *
    * @param f
    *   A binary function (a function that takes two arguments) that is applied to the current and next values emitted by this flow.
    * @return
    *   Combined value retrieved from running function `f` on all flow elements in a cumulative manner where result of the previous call is
    *   used as an input value to the next.
    * @throws NoSuchElementException
    *   When this flow is empty.
    */
  def runReduce[U >: T](f: (U, U) => U): U =
    var current: Option[U] = None
    last.run(
      new FlowSink[T]:
        override def apply(t: T): Unit = current match
          case None    => current = Some(t)
          case Some(c) => current = Some(f(c, t))
    )

    current.getOrElse(throw new NoSuchElementException("cannot reduce an empty flow"))
  end runReduce

  /** Returns the list of up to `n` last elements emitted by this flow. Less than `n` elements is returned when this flow emits less
    * elements than requested. [[List.empty]] is returned when `takeLast` is called on an empty flow.
    *
    * @param n
    *   Number of elements to be taken from the end of this flow. It is expected that `n >= 0`.
    * @return
    *   A list of up to `n` last elements from this flow.
    */
  def runTakeLast(n: Int): List[T] =
    require(n >= 0, "n must be >= 0")
    if n == 0 then
      runDrain()
      List.empty
    else if n == 1 then runLastOption().toList
    else
      val buffer: ListBuffer[T] = ListBuffer()
      buffer.sizeHint(n)

      last.run(
        new FlowSink[T]:
          override def apply(t: T): Unit =
            if buffer.size == n then buffer.dropInPlace(1)
            buffer.append(t)
      )

      buffer.result()
    end if
  end runTakeLast
end FlowRunOps
