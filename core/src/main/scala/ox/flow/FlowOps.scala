package ox.flow

import ox.CancellableFork
import ox.Fork
import ox.Ox
import ox.OxUnsupervised
import ox.channels.BufferCapacity
import ox.channels.Channel
import ox.channels.ChannelClosed
import ox.channels.Default
import ox.channels.Sink
import ox.channels.Source
import ox.channels.forkPropagate
import ox.channels.selectOrClosed
import ox.discard
import ox.flow.internal.groupByImpl
import ox.forkCancellable
import ox.forkUnsupervised
import ox.forkUser
import ox.repeatWhile
import ox.resilience.RetryConfig
import ox.scheduling.Schedule
import ox.sleep
import ox.supervised
import ox.tapException
import ox.unsupervised

import java.util.concurrent.Semaphore
import scala.concurrent.duration.DurationLong
import scala.concurrent.duration.FiniteDuration

class FlowOps[+T]:
  outer: Flow[T] =>

  /** When run, the current pipeline is run asynchornously in the background, emitting elements to a buffer. The elements of the buffer are
    * then emitted by the returned flow.
    *
    * The size of the buffer is determined by the [[BufferCapacity]] that is in scope.
    *
    * Any exceptions are propagated by the returned flow.
    */
  def buffer()(using BufferCapacity): Flow[T] = Flow.usingEmitInline: emit =>
    val ch = BufferCapacity.newChannel[T]
    unsupervised:
      runLastToChannelAsync(ch)
      FlowEmit.channelToEmit(ch, emit)

  //

  /** Applies the given mapping function `f` to each element emitted by this flow. The returned flow then emits the results.
    *
    * @param f
    *   The mapping function.
    */
  def map[U](f: T => U): Flow[U] = Flow.usingEmitInline: emit =>
    last.run(FlowEmit.fromInline(t => emit(f(t))))

  /** Applies the given mapping function `f` to each element emitted by this flow, in sequence. The given [[FlowSink]] can be used to emit
    * an arbirary number of elements.
    *
    * The `FlowEmit` instance provided to the `f` callback should only be used on the calling thread. That is, `FlowEmit` is thread-unsafe.
    * Moreover, the instance should not be stored or captured in closures, which outlive the invocation of `f`.
    *
    * @param f
    *   The mapping function.
    */
  def mapUsingEmit[U](f: T => FlowEmit[U] => Unit): Flow[U] = Flow.usingEmitInline: emit =>
    last.run(FlowEmit.fromInline(t => f(t)(emit)))

  /** Emits only those elements emitted by this flow, for which `f` returns `true`.
    *
    * @param f
    *   The filtering function.
    */
  def filter(f: T => Boolean): Flow[T] = Flow.usingEmitInline: emit =>
    last.run(FlowEmit.fromInline(t => if f(t) then emit.apply(t)))

  /** Emits only every nth element emitted by this flow.
    *
    * @param n
    *   The interval between two emitted elements.
    */
  def sample(n: Int): Flow[T] = Flow.usingEmitInline: emit =>
    var sampleCounter = 0
    last.run(
      FlowEmit.fromInline: t =>
        sampleCounter += 1
        if n != 0 && sampleCounter % n == 0 then emit(t)
    )

  /** Remove subsequent, repeating elements
    */
  def debounce: Flow[T] =
    debounceBy(identity)

  /** Remove subsequent, repeating elements matching 'f'
    *
    * @param f
    *   The function used to compare the previous and current elements
    */
  def debounceBy[U](f: T => U): Flow[T] = Flow.usingEmitInline: emit =>
    var previousElement: Option[U] = None
    last.run(
      FlowEmit.fromInline: t =>
        val currentElement = f(t)
        if !previousElement.contains(currentElement) then emit(t)
        previousElement = Some(currentElement)
    )

  /** Applies the given mapping function `f` to each element emitted by this flow, for which the function is defined, and emits the result.
    * If `f` is not defined at an element, the element will be skipped.
    *
    * @param f
    *   The mapping function.
    */
  def collect[U](f: PartialFunction[T, U]): Flow[U] = Flow.usingEmitInline: emit =>
    last.run(FlowEmit.fromInline(t => if f.isDefinedAt(t) then emit.apply(f(t))))

  /** Transforms the elements of the flow by applying an accumulation function to each element, producing a new value at each step. The
    * resulting flow contains the accumulated values at each point in the original flow.
    *
    * @param initial
    *   The initial value to start the accumulation.
    * @param f
    *   The accumulation function that is applied to each element of the flow.
    */
  def scan[V](initial: V)(f: (V, T) => V): Flow[V] = Flow.usingEmitInline: emit =>
    emit(initial)
    var accumulator = initial
    last.run(
      FlowEmit.fromInline: t =>
        accumulator = f(accumulator, t)
        emit(accumulator)
    )

  /** Applies the given effectful function `f` to each element emitted by this flow. The returned flow emits the elements unchanged. If `f`
    * throws an exceptions, the flow fails and propagates the exception.
    */
  def tap(f: T => Unit): Flow[T] = map(t =>
    f(t); t
  )

  /** Applies the given mapping function `f` to each element emitted by this flow, obtaining a nested flow to run. The elements emitted by
    * the nested flow are then emitted by the returned flow.
    *
    * The nested flows are run in sequence, that is, the next nested flow is started only after the previous one completes.
    *
    * @param f
    *   The mapping function.
    */
  def flatMap[U](f: T => Flow[U]): Flow[U] = Flow.usingEmitInline: emit =>
    last.run(
      FlowEmit.fromInline: t =>
        f(t).runToEmit(emit)
    )

  /** Intersperses elements emitted by this flow with `inject` elements. The `inject` element is emitted between each pair of elements. */
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

  private def intersperse[U >: T](start: Option[U], inject: U, end: Option[U]): Flow[U] = Flow.usingEmitInline: emit =>
    start.foreach(emit.apply)
    var firstEmitted = false
    last.run(
      FlowEmit.fromInline: u =>
        if firstEmitted then emit(inject)
        emit(u)
        firstEmitted = true
    )
    end.foreach(emit.apply)

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
  def mapPar[U](parallelism: Int)(f: T => U)(using BufferCapacity): Flow[U] = Flow.usingEmitInline: emit =>
    val s = new Semaphore(parallelism)
    // doubling the capacity to allow for some slack after the mapping is completed, but its result not yet received
    // then, new mappings can already be started
    val inProgress = Channel.withCapacity[Fork[Option[U]]](parallelism * 2)
    val results = BufferCapacity.newChannel[U]

    def forkMapping(t: T)(using OxUnsupervised): Fork[Option[U]] =
      forkUnsupervised:
        try
          val u = f(t)
          s.release() // not in finally, as in case of an exception, no point in starting subsequent forks
          Some(u)
        catch
          case t: Throwable => // same as in `forkPropagate`, catching all exceptions
            results.errorOrClosed(t).discard
            None

    // creating a nested scope, so that in case of errors, we can clean up any mapping forks in a "local" fashion,
    // that is without closing the main scope; any error management must be done in the forks, as the scope is
    // unsupervised
    unsupervised:
      // a fork which runs the `last` pipeline, and for each emitted element creates a fork
      // notifying only the `results` channels, as it will cause the scope to end, and any other forks to be
      // interrupted, including the inProgress-fork, which might be waiting on a join()
      forkPropagate(results):
        last.run(
          FlowEmit.fromInline: t =>
            s.acquire()
            inProgress.sendOrClosed(forkMapping(t)).discard
        )
        inProgress.doneOrClosed().discard

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
      .discard

      // in the main body, we call the `emit` methods using the (sequentially received) results; when an error occurs,
      // the scope ends, interrupting any forks that are still running
      FlowEmit.channelToEmit(results, emit)
  end mapPar

  /** Applies the given mapping function `f` to each element emitted by this flow. At most `parallelism` invocations of `f` are run in
    * parallel.
    *
    * The mapped results **might** be emitted out-of-order, depending on the order in which the mapping function completes.
    *
    * @param parallelism
    *   An upper bound on the number of forks that run in parallel. Each fork runs the function `f` on a single element from the flow.
    * @param f
    *   The mapping function.
    */
  def mapParUnordered[U](parallelism: Int)(f: T => U)(using BufferCapacity): Flow[U] = Flow.usingEmitInline: emit =>
    val results = BufferCapacity.newChannel[U]
    val s = new Semaphore(parallelism)
    unsupervised: // the outer scope, used for the fork which runs the `last` pipeline
      forkPropagate(results):
        supervised: // the inner scope, in which user forks are created, and which is used to wait for all to complete when done
          last
            .run(
              FlowEmit.fromInline: t =>
                s.acquire()
                forkUser:
                  try
                    results.sendOrClosed(f(t)).discard
                    s.release()
                  catch case t: Throwable => results.errorOrClosed(t).discard
                .discard
            )
            // if run() throws, we want this to become the "main" error, instead of an InterruptedException
            .tapException(results.errorOrClosed(_).discard)
        results.doneOrClosed().discard

      FlowEmit.channelToEmit(results, emit)

  private val abortTake = new Exception("abort take")

  /** Takes the first `n` elements from this flow and emits them. If the flow completes before emitting `n` elements, the returned flow
    * completes as well.
    */
  def take(n: Int): Flow[T] = Flow.usingEmitInline: emit =>
    var taken = 0
    try
      last.run(
        FlowEmit.fromInline: t =>
          if taken < n then
            emit(t)
            taken += 1

          if taken == n then throw abortTake
      )
    catch case `abortTake` => () // done
    end try

  /** Transform the flow so that it emits elements as long as predicate `f` is satisfied (returns `true`). If `includeFirstFailing` is
    * `true`, the flow will additionally emit the first element that failed the predicate. After that, the flow will complete as done.
    *
    * @param f
    *   A predicate function called on incoming elements.
    * @param includeFirstFailing
    *   Whether the flow should also emit the first element that failed the predicate (`false` by default).
    */
  def takeWhile(f: T => Boolean, includeFirstFailing: Boolean = false): Flow[T] = Flow.usingEmitInline: emit =>
    try
      last.run(
        FlowEmit.fromInline: t =>
          if f(t) then emit(t)
          else
            if includeFirstFailing then emit.apply(t)
            throw abortTake
      )
    catch case `abortTake` => () // done

  /** Drops `n` elements from this flow and emits subsequent elements.
    *
    * @param n
    *   Number of elements to be dropped.
    */
  def drop(n: Int): Flow[T] = Flow.usingEmitInline: emit =>
    var dropped = 0
    last.run(
      FlowEmit.fromInline: t =>
        if dropped < n then dropped += 1
        else emit(t)
    )

  /** Merges two flows into a single flow. The resulting flow emits elements from both flows in the order they are emitted. If one of the
    * flows completes before the other, the remaining elements from the other flow are emitted by the returned flow. This can be changed
    * with the `propagateDoneLeft` and `propagateDoneRight` flags.
    *
    * Both flows are run concurrently in the background. The size of the buffers is determined by the [[BufferCapacity]] that is in scope.
    *
    * @param other
    *   The flow to be merged with this flow.
    * @param propagateDoneLeft
    *   Should the resulting flow complete when the left flow (`this`) completes, before the `other` flow. By default `false`, that is any
    *   remaining elements from the `other` flow are emitted.
    * @param propagateDoneRight
    *   Should the resulting flow complete when the right flow (`outer`) completes, before `this` flow. By default `false`, that is any
    *   remaining elements from `this` flow are emitted.
    */
  def merge[U >: T](other: Flow[U], propagateDoneLeft: Boolean = false, propagateDoneRight: Boolean = false)(using
      BufferCapacity
  ): Flow[U] =
    Flow.usingEmitInline: emit =>
      unsupervised:
        val c1 = outer.runToChannel()
        val c2 = other.runToChannel()

        repeatWhile:
          selectOrClosed(c1, c2) match
            case ChannelClosed.Done =>
              if c1.isClosedForReceive then
                if !propagateDoneLeft then FlowEmit.channelToEmit(c2, emit)
              else if !propagateDoneRight then FlowEmit.channelToEmit(c1, emit)
              false
            case e: ChannelClosed.Error => throw e.toThrowable
            case r: U @unchecked        => emit(r); true

  /** Given that this flow emits other flows, flattens the nested flows into a single flow. The resulting flow emits elements from the
    * nested flows in the order they are emitted.
    *
    * The nested flows are run in sequence, that is, the next nested flow is started only after the previous one completes.
    */
  def flatten[U](using T <:< Flow[U]): Flow[U] = this.flatMap(identity)

  /** Pipes the elements of child flows into the returned flow.
    *
    * If the this flow or any of the child flows emit an error, the pulling stops and the output flow propagates the error.
    *
    * Up to [[parallelism]] child flows are run concurrently in the background. When the limit is reached, until a child flow completes, no
    * more child flows are run.
    *
    * The size of the buffers for the elements emitted by the child flows is determined by the [[BufferCapacity]] that is in scope.
    *
    * @param parallelism
    *   An upper bound on the number of child flows that run in parallel.
    */
  def flattenPar[U](parallelism: Int)(using T <:< Flow[U])(using BufferCapacity): Flow[U] = Flow.usingEmitInline: emit =>
    case class Nested(child: Flow[U])
    case object ChildDone

    unsupervised:
      val childOutputChannel = BufferCapacity.newChannel[U]
      val childDoneChannel = Channel.unlimited[ChildDone.type]

      // When an error occurs in the parent, propagating it also to `childOutputChannel`, from which we always
      // `select` in the main loop. That way, even if max parallelism is reached, errors in the parent will
      // be discovered without delay.
      val parentChannel = outer.map(Nested(_)).onError(childOutputChannel.error(_).discard).runToChannel()

      var runningChannelCount = 1 // parent is running
      var parentDone = false

      while runningChannelCount > 0 do
        assert(runningChannelCount <= parallelism + 1)

        val pool: List[Source[Nested] | Source[U] | Source[ChildDone.type]] =
          // +1, because of the parent channel.
          if runningChannelCount == parallelism + 1 || parentDone then List(childOutputChannel, childDoneChannel)
          else List(childOutputChannel, childDoneChannel, parentChannel)

        selectOrClosed(pool) match
          // Only `parentChannel` might be done, child completion is signalled via `childDoneChannel`.
          case ChannelClosed.Done =>
            parentDone = isSourceDone(parentChannel)
            assert(parentDone)

            runningChannelCount -= 1

          case e: ChannelClosed.Error => throw e.toThrowable

          case ChildDone => runningChannelCount -= 1

          case Nested(t) =>
            forkUnsupervised:
              t.onDone(childDoneChannel.send(ChildDone)).runPipeToSink(childOutputChannel, propagateDone = false)
            .discard

            runningChannelCount += 1

          case u: U @unchecked => emit(u)
        end match
      end while
  end flattenPar

  /** Concatenates this flow with the `other` flow. The resulting flow will emit elements from this flow first, and then from the `other`
    * flow.
    *
    * @param other
    *   The flow to be appended to this flow.
    */
  def concat[U >: T](other: Flow[U]): Flow[U] = Flow.concat(List(this, other))

  /** Alias for [[concat]]. */
  def ++[U >: T](other: Flow[U]): Flow[U] = concat(other)

  /** Prepends `other` flow to this source. The resulting flow will emit elements from `other` flow first, and then from the this flow.
    *
    * @param other
    *   The flow to be prepended to this flow.
    */
  def prepend[U >: T](other: Flow[U]): Flow[U] = Flow.concat(List(other, this))

  /** Combines elements from this and the other flow into tuples. Completion of either flow completes the returned flow as well. The flows
    * are run concurrently.
    * @see
    *   zipAll
    */
  def zip[U](other: Flow[U]): Flow[(T, U)] = Flow.usingEmitInline: emit =>
    unsupervised:
      val s1 = outer.runToChannel()
      val s2 = other.runToChannel()

      repeatWhile:
        s1.receiveOrClosed() match
          case ChannelClosed.Done     => false
          case e: ChannelClosed.Error => throw e.toThrowable
          case t: T @unchecked =>
            s2.receiveOrClosed() match
              case ChannelClosed.Done     => false
              case e: ChannelClosed.Error => throw e.toThrowable
              case u: U @unchecked        => emit((t, u)); true

  /** Combines elements from this and the other flow into tuples, handling early completion of either flow with defaults. The flows are run
    * concurrently.
    *
    * @param other
    *   A flow of elements to be combined with.
    * @param thisDefault
    *   A default element to be used in the result tuple when the other flow is longer.
    * @param otherDefault
    *   A default element to be used in the result tuple when the current flow is longer.
    */
  def zipAll[U >: T, V](other: Flow[V], thisDefault: U, otherDefault: V): Flow[(U, V)] = Flow.usingEmitInline: emit =>
    unsupervised:
      val s1 = outer.runToChannel()
      val s2 = other.runToChannel()

      def receiveFromOther(thisElement: U, otherDoneHandler: () => Boolean): Boolean =
        s2.receiveOrClosed() match
          case ChannelClosed.Done     => otherDoneHandler()
          case e: ChannelClosed.Error => throw e.toThrowable
          case v: V @unchecked        => emit((thisElement, v)); true

      repeatWhile:
        s1.receiveOrClosed() match
          case ChannelClosed.Done =>
            receiveFromOther(
              thisDefault,
              () => false
            )
          case e: ChannelClosed.Error => throw e.toThrowable
          case t: T @unchecked =>
            receiveFromOther(
              t,
              () =>
                emit((t, otherDefault)); true
            )

  /** Combines each element from this and the index of the element (starting at 0).
    */
  def zipWithIndex: Flow[(T, Long)] = Flow.usingEmitInline: emit =>
    var index = 0L
    last.run(
      FlowEmit.fromInline: t =>
        val zipped = (t, index)
        index += 1
        emit(zipped)
    )

  /** Emits a given number of elements (determined byc `segmentSize`) from this flow to the returned flow, then emits the same number of
    * elements from the `other` flow and repeats. The order of elements in both flows is preserved.
    *
    * If one of the flows is done before the other, the behavior depends on the `eagerCancel` flag. When set to `true`, the returned flow is
    * completed immediately, otherwise the remaining elements from the other flow are emitted by the returned flow.
    *
    * Both flows are run concurrently and asynchronously.
    *
    * @param other
    *   The flow whose elements will be interleaved with the elements of this flow.
    * @param segmentSize
    *   The number of elements sent from each flow before switching to the other one. Default is 1.
    * @param eagerComplete
    *   If `true`, the returned flow is completed as soon as either of the flow completes. If `false`, the remaining elements of the
    *   non-completed flow are sent downstream.
    */
  def interleave[U >: T](other: Flow[U], segmentSize: Int = 1, eagerComplete: Boolean = false)(using BufferCapacity): Flow[U] =
    Flow.interleaveAll(List(this, other), segmentSize, eagerComplete)

  /** Applies the given mapping function `f`, using additional state, to each element emitted by this flow. The results are emitted by the
    * returned flow. Optionally the returned flow emits an additional element, possibly based on the final state, once this flow is done.
    *
    * The `initializeState` function is called once when `statefulMap` is called.
    *
    * The `onComplete` function is called once when this flow is done. If it returns a non-empty value, the value will be emitted by the
    * flow, while an empty value will be ignored.
    *
    * @param initializeState
    *   A function that initializes the state.
    * @param f
    *   A function that transforms the element from this flow and the state into a pair of the next state and the result which is emitted by
    *   the returned flow.
    * @param onComplete
    *   A function that transforms the final state into an optional element emitted by the returned flow. By default the final state is
    *   ignored.
    */
  def mapStateful[S, U](initializeState: => S)(f: (S, T) => (S, U), onComplete: S => Option[U] = (_: S) => None): Flow[U] =
    def resultToSome(s: S, t: T) =
      val (newState, result) = f(s, t)
      (newState, Some(result))

    mapStatefulConcat(initializeState)(resultToSome, onComplete)
  end mapStateful

  /** Applies the given mapping function `f`, using additional state, to each element emitted by this flow. The returned flow emits the
    * results one by one. Optionally the returned flow emits an additional element, possibly based on the final state, once this flow is
    * done.
    *
    * The `initializeState` function is called once when `statefulMap` is called.
    *
    * The `onComplete` function is called once when this flow is done. If it returns a non-empty value, the value will be emitted by the
    * returned flow, while an empty value will be ignored.
    *
    * @param initializeState
    *   A function that initializes the state.
    * @param f
    *   A function that transforms the element from this flow and the state into a pair of the next state and a
    *   [[scala.collection.IterableOnce]] of results which are emitted one by one by the returned flow. If the result of `f` is empty,
    *   nothing is emitted by the returned flow.
    * @param onComplete
    *   A function that transforms the final state into an optional element emitted by the returned flow. By default the final state is
    *   ignored.
    */
  def mapStatefulConcat[S, U](
      initializeState: => S
  )(f: (S, T) => (S, IterableOnce[U]), onComplete: S => Option[U] = (_: S) => None): Flow[U] = Flow.usingEmitInline: emit =>
    var state = initializeState
    last.run(
      FlowEmit.fromInline: t =>
        val (nextState, result) = f(state, t)
        state = nextState
        result.iterator.foreach(emit.apply)
    )
    onComplete(state).foreach(emit.apply)
  end mapStatefulConcat

  /** Applies the given mapping function `f`, to each element emitted by this source, transforming it into an [[IterableOnce]] of results,
    * then the returned flow emits the results one by one. Can be used to unfold incoming sequences of elements into single elements.
    *
    * @param f
    *   A function that transforms the element from this flow into an [[IterableOnce]] of results which are emitted one by one by the
    *   returned flow. If the result of `f` is empty, nothing is emitted by the returned channel.
    */
  def mapConcat[U](f: T => IterableOnce[U]): Flow[U] = Flow.usingEmitInline: emit =>
    last.run(
      FlowEmit.fromInline: t =>
        f(t).iterator.foreach(emit.apply)
    )

  /** Emits elements limiting the throughput to specific number of elements (evenly spaced) per time unit. Note that the element's
    * emission-time time is included in the resulting throughput. For instance having `throttle(1, 1.second)` and emission of the next
    * element taking `Xms` means that resulting flow will emit elements every `1s + Xms` time. Throttling is not applied to the empty
    * source.
    *
    * @param elements
    *   Number of elements to be emitted. Must be greater than 0.
    * @param per
    *   Per time unit. Must be greater or equal to 1 ms.
    * @return
    *   A flow that emits at most `elements` `per` time unit.
    */
  def throttle(elements: Int, per: FiniteDuration): Flow[T] =
    require(elements > 0, "elements must be > 0")
    require(per.toMillis > 0, "per time must be >= 1 ms")
    val emitEveryMillis = (per.toMillis / elements).millis
    tap(t => sleep(emitEveryMillis))
  end throttle

  /** If this flow has no elements then elements from an `alternative` flow are emitted by the returned flow. If this flow is failed then
    * the returned flow is failed as well.
    *
    * @param alternative
    *   An alternative flow to be used when this flow is empty.
    */
  def orElse[U >: T](alternative: Flow[U]): Flow[U] = Flow.usingEmitInline: emit =>
    var receivedAtLeastOneElement = false
    last.run(
      FlowEmit.fromInline: t =>
        emit(t)
        receivedAtLeastOneElement = true
    )
    if !receivedAtLeastOneElement then alternative.runToEmit(emit)
  end orElse

  /** Chunks up the elements into groups of the specified size. The last group may be smaller due to the flow being complete.
    *
    * @param n
    *   The number of elements in a group.
    */
  def grouped(n: Int): Flow[Seq[T]] = groupedWeighted(n)(_ => 1)

  /** Chunks up the elements into groups that have a cumulative weight greater or equal to the `minWeight`. The last group may be smaller
    * due to the flow being complete.
    *
    * @param minWeight
    *   The minimum cumulative weight of elements in a group.
    * @param costFn
    *   The function that calculates the weight of an element.
    */
  def groupedWeighted(minWeight: Long)(costFn: T => Long): Flow[Seq[T]] =
    require(minWeight > 0, "minWeight must be > 0")

    Flow.usingEmitInline: emit =>
      var buffer = Vector.empty[T]
      var accumulatedCost = 0L
      last.run(
        FlowEmit.fromInline: t =>
          buffer = buffer :+ t

          accumulatedCost += costFn(t)

          if accumulatedCost >= minWeight then
            emit.apply(buffer)
            buffer = Vector.empty
            accumulatedCost = 0
      )
      if buffer.nonEmpty then emit.apply(buffer)
  end groupedWeighted

  /** Chunks up the emitted elements into groups, within a time window, or limited by the specified number of elements, whatever happens
    * first. The timeout is reset after a group is emitted. If timeout expires and the buffer is empty, nothing is emitted. As soon as a new
    * element is emitted, the flow will emit it as a single-element group and reset the timer.
    *
    * @param n
    *   The maximum number of elements in a group.
    * @param duration
    *   The time window in which the elements are grouped.
    */
  def groupedWithin(n: Int, duration: FiniteDuration)(using BufferCapacity): Flow[Seq[T]] = groupedWeightedWithin(n, duration)(_ => 1)

  private case object GroupingTimeout

  /** Chunks up the emitted elements into groups, within a time window, or limited by the cumulative weight being greater or equal to the
    * `minWeight`, whatever happens first. The timeout is reset after a group is emitted. If timeout expires and the buffer is empty,
    * nothing is emitted. As soon as a new element is received, the flow will emit it as a single-element group and reset the timer.
    *
    * @param minWeight
    *   The minimum cumulative weight of elements in a group if no timeout happens.
    * @param duration
    *   The time window in which the elements are grouped.
    * @param costFn
    *   The function that calculates the weight of an element.
    */
  def groupedWeightedWithin(minWeight: Long, duration: FiniteDuration)(costFn: T => Long)(using BufferCapacity): Flow[Seq[T]] =
    require(minWeight > 0, "minWeight must be > 0")
    require(duration > 0.seconds, "duration must be > 0")

    Flow.usingEmitInline: emit =>
      unsupervised:
        val c = outer.runToChannel()
        val c2 = BufferCapacity.newChannel[Seq[T]]
        val timerChannel = BufferCapacity.newChannel[GroupingTimeout.type]
        forkPropagate(c2):
          var buffer = Vector.empty[T]
          var accumulatedCost: Long = 0

          def forkTimeout() = forkCancellable:
            sleep(duration)
            timerChannel.sendOrClosed(GroupingTimeout).discard

          var timeoutFork: Option[CancellableFork[Unit]] = Some(forkTimeout())

          def sendBufferAndForkNewTimeout(): Unit =
            c2.send(buffer)
            buffer = Vector.empty
            accumulatedCost = 0
            timeoutFork.foreach(_.cancelNow())
            timeoutFork = Some(forkTimeout())

          repeatWhile:
            selectOrClosed(c.receiveClause, timerChannel.receiveClause) match
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
              case c.Received(t) =>
                buffer = buffer :+ t

                accumulatedCost += costFn(t).tapException(_ => timeoutFork.foreach(_.cancelNow()))

                if timeoutFork.isEmpty || accumulatedCost >= minWeight then
                  // timeout passed when buffer was empty or buffer full
                  sendBufferAndForkNewTimeout()

                true

        FlowEmit.channelToEmit(c2, emit)
  end groupedWeightedWithin

  /** Creates sliding windows of elements from this flow. The window slides by `step` elements. The last window may be smaller due to flow
    * being completed.
    *
    * @param n
    *   The number of elements in a window.
    * @param step
    *   The number of elements the window slides by.
    */
  def sliding(n: Int, step: Int = 1): Flow[Seq[T]] =
    require(n > 0, "n must be > 0")
    require(step > 0, "step must be > 0")

    Flow.usingEmitInline: emit =>
      var buffer = Vector.empty[T]
      last.run(
        FlowEmit.fromInline: t =>
          buffer = buffer :+ t
          if buffer.size < n then () // do nothing
          else if buffer.size == n then emit(buffer)
          else if step <= n then
            // if step is <= n we simply drop `step` elements and continue appending until buffer size is n
            buffer = buffer.drop(step)
            // in special case when step == 1, we have to send the buffer immediately
            if buffer.size == n then emit(buffer)
          else
            // step > n -  we drop `step` elements and continue appending until buffer size is n
            if buffer.size == step then buffer = buffer.drop(step)
          end if
      )
      // send the remaining elements, only if these elements were not yet sent
      if buffer.nonEmpty && buffer.size < n then emit(buffer)
  end sliding

  /** Breaks the input into chunks where the delimiter matches the predicate. The delimiter does not appear in the output. Two adjacent
    * delimiters in the input result in an empty chunk in the output.
    *
    * @param delimiter
    *   A predicate function that identifies delimiter elements.
    * @example
    *   {{{ scala> Flow.fromIterable(0 to 9).split(_ % 4 == 0).runToList() res0: List[Seq[Int]] = List(Seq(), Seq(1, 2, 3), Seq(5, 6, 7),
    *   Seq(9)) }}}
    */
  def split(delimiter: T => Boolean): Flow[Seq[T]] = Flow.usingEmitInline: emit =>
    var buffer = Vector.empty[T]
    last.run(
      FlowEmit.fromInline: t =>
        if delimiter(t) then
          // Delimiter found - emit current buffer and start a new one
          emit(buffer)
          buffer = Vector.empty
        else
          // Non-delimiter element - add to current buffer
          buffer = buffer :+ t
    )
    // Emit the final buffer if it's not empty
    if buffer.nonEmpty then emit(buffer)
  end split

  /** Breaks the input into chunks delimited by the given sequence of elements. The delimiter sequence does not appear in the output. Two
    * adjacent delimiter sequences in the input result in an empty chunk in the output.
    *
    * @param delimiter
    *   A sequence of elements that serves as a delimiter. If empty, the entire input is returned as a single chunk.
    * @example
    *   {{{scala> Flow.fromValues(1, 2, 0, 0, 3, 4, 0, 0, 5).splitOn(List(0, 0)).runToList() res0: List[Seq[Int]] = List(Seq(1, 2), Seq(3, 4), Seq(5))}}}
    */
  def splitOn[U >: T](delimiter: List[U]): Flow[Seq[T]] = Flow.usingEmitInline: emit =>
    if delimiter.isEmpty then
      // Empty delimiter means no splitting - emit entire input as single chunk
      var buffer = Vector.empty[T]
      last.run(FlowEmit.fromInline(t => buffer = buffer :+ t))
      if buffer.nonEmpty then emit(buffer)
    else
      val delimiterAsVector = delimiter.toVector

      var buffer = Vector.empty[T]
      var delimiterBuffer = Vector.empty[T] // Buffer to track potential delimiter matches

      def emitBufferAndReset(): Unit =
        emit(buffer)
        buffer = Vector.empty
        delimiterBuffer = Vector.empty

      def processElement(element: T): Unit =
        // Add element to delimiter buffer
        delimiterBuffer = delimiterBuffer :+ element

        // Move excess elements from delimiter buffer to main buffer
        while delimiterBuffer.length > delimiter.length do
          delimiterBuffer match
            case head +: tail =>
              buffer = buffer :+ head
              delimiterBuffer = tail
            case _ => // Should never happen since length > delimiter.length
        // Check if delimiter buffer exactly matches the delimiter
        if delimiterBuffer.length == delimiter.length then
          if delimiterBuffer == delimiterAsVector then
            // Found complete delimiter - emit buffer and reset
            emitBufferAndReset()
          end if
        end if
      end processElement

      last.run(FlowEmit.fromInline(processElement))

      // Handle remaining elements in delimiter buffer
      buffer = buffer ++ delimiterBuffer
      if buffer.nonEmpty then emit(buffer)
    end if
  end splitOn

  /** Attaches the given [[ox.channels.Sink]] to this flow, meaning elements that pass through will also be sent to the sink. If emitting an
    * element, or sending to the `other` sink blocks, no elements will be processed until both are done. The elements are first emitted by
    * the flow and then, only if that was successful, to the `other` sink.
    *
    * If this flow fails, then failure is passed to the `other` sink as well. If the `other` sink is failed or complete, this becomes a
    * failure of the returned flow (contrary to [[alsoToTap]] where it's ignored).
    *
    * @param other
    *   The sink to which elements from this flow will be sent.
    * @see
    *   [[alsoToTap]] for a version that drops elements when the `other` sink is not available for receive.
    */
  def alsoTo[U >: T](other: Sink[U]): Flow[U] = Flow.usingEmitInline: emit =>
    {
      last.run(
        FlowEmit.fromInline: t =>
          emit(t).tapException(e => other.errorOrClosed(e).discard)
          other.send(t)
      )
      other.done()
    }.tapException(other.errorOrClosed(_).discard)
  end alsoTo

  private case object NotSent

  /** Attaches the given [[ox.channels.Sink]] to this flow, meaning elements that pass through will also be sent to the sink. If the `other`
    * sink is not available for receive, the elements are still emitted by the returned flow, but not sent to the `other` sink.
    *
    * If this flow fails, then failure is passed to the `other` sink as well. If the `other` sink fails or closes, then failure or closure
    * is ignored and it doesn't affect the resulting flow (contrary to [[alsoTo]] where it's propagated).
    *
    * @param other
    *   The sink to which elements from this source will be sent.
    * @see
    *   [[alsoTo]] for a version that ensures that elements are emitted both by the returned flow and sent to the `other` sink.
    */
  def alsoToTap[U >: T](other: Sink[U]): Flow[U] = Flow.usingEmitInline: emit =>
    {
      last.run(
        FlowEmit.fromInline: t =>
          emit(t).tapException(e => other.errorOrClosed(e).discard)
          selectOrClosed(other.sendClause(t), Default(NotSent)).discard
      )
      other.doneOrClosed().discard
    }.tapException(other.errorOrClosed(_).discard)
  end alsoToTap

  /** Groups elements emitted by this flow into child flows. Elements for which [[predicate]] returns the same value (of type `V`) end up in
    * the same child flow. [[childFlowTransform]] is applied to each created child flow, and the resulting flow is run in the background.
    * Finally, the child flows are merged back, that is any elements that they emit are emitted by the returned flow.
    *
    * Up to [[parallelism]] child flows are run concurrently in the background. When the limit is reached, the child flow which didn't
    * receive a new element the longest is completed as done.
    *
    * Child flows for `V` values might be created multiple times (if, after completing a child flow because of parallelism limit, new
    * elements arrive, mapped to a given `V` value). However, it is guaranteed that for a given `V` value, there will be at most one child
    * flow running at any time.
    *
    * Child flows should only complete as done when the flow of received `T` elements completes. Otherwise, the entire stream will fail with
    * an error.
    *
    * Errors that occur in this flow, or in any child flows, become errors of the returned flow (exceptions are wrapped in
    * [[ChannelClosedException]]).
    *
    * The size of the buffers for the elements emitted by this flow (which is also run in the background) and the child flows are determined
    * by the [[BufferCapacity]] that is in scope.
    *
    * @param parallelism
    *   An upper bound on the number of child flows that run in parallel at any time.
    * @param predicate
    *   Function used to determine the group for an element of type `T`. Each group is represented by a value of type `V`.
    * @param childFlowTransform
    *   The function that is used to create a child flow, which is later in the background. The arguments are the group value, for which the
    *   flow is created, and a flow of `T` elements in that group (each such element has the same group value `V` returned by `predicated`).
    */
  def groupBy[V, U](parallelism: Int, predicate: T => V)(childFlowTransform: V => Flow[T] => Flow[U])(using BufferCapacity): Flow[U] =
    groupByImpl(outer, parallelism, predicate)(childFlowTransform)

  /** Discard all elements emitted by this flow. The returned flow completes only when this flow completes (successfully or with an error).
    */
  def drain(): Flow[Nothing] = Flow.usingEmitInline: emit =>
    last.run(FlowEmit.fromInline(_ => ()))

  /** Always runs `f` after the flow completes, whether it's because all elements are emitted, or when there's an error. */
  def onComplete(f: => Unit): Flow[T] = Flow.usingEmitInline: emit =>
    try last.run(emit)
    finally f

  /** Runs `f` after the flow completes successfully, that is when all elements are emitted. */
  def onDone(f: => Unit): Flow[T] = Flow.usingEmitInline: emit =>
    last.run(emit)
    f

  /** Runs `f` after the flow completes with an error. The error can't be recovered. */
  def onError(f: Throwable => Unit): Flow[T] = Flow.usingEmitInline: emit =>
    last.run(emit).tapException(f)

  /** Retries the upstream flow execution using the provided retry configuration. If the flow fails with an exception, it will be retried
    * according to the schedule defined in the retry config until it succeeds or the retry policy decides to stop.
    *
    * Each retry attempt will run the complete upstream flow, from start up to this point. The retry behavior is controlled by the
    * [[RetryConfig]].
    *
    * Note that this retries the flow execution itself, not individual elements within the flow. If you need to retry individual operations
    * within the flow, consider using retry logic inside methods such as [[map]].
    *
    * Creates an asynchronous boundary (see [[buffer]]) to isolate failures when running the upstream flow.
    *
    * @param config
    *   The retry configuration that specifies the retry schedule and success/failure conditions.
    * @return
    *   A new flow that will retry execution according to the provided configuration.
    * @throws anything
    *   The exception from the last retry attempt if all retries are exhausted.
    * @see
    *   [[ox.resilience.retry]]
    */
  def retry(config: RetryConfig[Throwable, Unit])(using BufferCapacity): Flow[T] = Flow.usingEmitInline: emit =>
    val ch = BufferCapacity.newChannel[T]
    unsupervised:
      forkPropagate(ch) {
        ox.resilience.retry(config)(last.run(FlowEmit.fromInline(t => ch.send(t))))
        ch.done()
      }.discard
      FlowEmit.channelToEmit(ch, emit)

  /** @see
    *   [[retry(RetryConfig)]]
    */
  def retry(schedule: Schedule): Flow[T] = retry(RetryConfig(schedule))

  /** Recovers from errors in the upstream flow by emitting a recovery value when the error is handled by the partial function. If the
    * partial function is not defined for the error, the original error is propagated.
    *
    * Creates an asynchronous boundary (see [[buffer]]) to isolate failures when running the upstream flow.
    *
    * @param pf
    *   A partial function that handles specific exceptions and returns a recovery value to emit.
    * @return
    *   A flow that emits elements from the upstream flow, and emits a recovery value if the upstream fails with a handled exception.
    */
  def recover[U >: T](pf: PartialFunction[Throwable, U])(using BufferCapacity): Flow[U] = Flow.usingEmitInline: emit =>
    val ch = BufferCapacity.newChannel[U]
    unsupervised:
      forkPropagate(ch) {
        try last.run(FlowEmit.fromInline(t => ch.send(t)))
        catch case e: Throwable if pf.isDefinedAt(e) => ch.send(pf(e))
        ch.done()
      }.discard
      FlowEmit.channelToEmit(ch, emit)

  //

  protected def runLastToChannelAsync(ch: Sink[T])(using OxUnsupervised): Unit =
    forkPropagate(ch) {
      last.run(FlowEmit.fromInline(t => ch.send(t)))
      ch.done()
    }.discard
end FlowOps

private[flow] inline def isSourceDone(ch: Source[?]) = ch.isClosedForReceiveDetail.contains(ChannelClosed.Done)
