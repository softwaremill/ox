package ox.flow.internal

import ox.flow.Flow
import ox.flow.isSourceDone
import ox.channels.BufferCapacity
import ox.channels.Channel
import ox.channels.Sink
import ox.OxUnsupervised
import ox.forkUnsupervised
import ox.discard
import ox.unsupervised
import ox.channels.selectOrClosed
import ox.channels.ChannelClosed

private[flow] def groupByImpl[T, V, U](parent: Flow[T], parallelism: Int, predicate: T => V)(childFlowTransform: V => Flow[T] => Flow[U])(
    using BufferCapacity
): Flow[U] =
  // State, which is updated in the main `repeatWhile`+`select` loop below.
  case class GroupByState(
      parentDone: Boolean,
      pendingFromParent: Option[(T, V, Long)],
      children: Map[V, Channel[T]],
      // Counter of elements received from the parent.
      fromParentCounter: Long,
      // A heap with group value (`V`) elements, weighted by the last parent element, mapped to that value.
      // Used to complete child flows, which haven't received an element the longest. Mutable!
      childMostRecentCounters: WeightedHeap[V]
  ):
    def withChildAdded(v: V, childChannel: Channel[T]): GroupByState = copy(children = children + (v -> childChannel))
    def withChildRemoved(v: V): GroupByState = copy(children = children.removed(v))
    def withPendingFromParent(t: T, v: V, counter: Long): GroupByState = copy(pendingFromParent = Some((t, v, counter)))
    def withParentDone(done: Boolean): GroupByState = copy(parentDone = done)
    def withFromParentCounterIncremented: GroupByState = copy(fromParentCounter = fromParentCounter + 1)

    def elementsCanBeReceived: Boolean = !(children.isEmpty && parentDone)
    def shouldReceiveFromParentChannel: Boolean = parentDone || pendingFromParent.isDefined
  end GroupByState

  case class ChildDone(v: V)

  // Running a pending child flow, after another has completed as done
  def runChild_ifPending(state: GroupByState, childDone: Sink[ChildDone], childOutput: Sink[U])(using OxUnsupervised): GroupByState =
    state.pendingFromParent match
      case Some((t, v, counter)) =>
        sendToChild_orRunChild_orBuffer(state.copy(pendingFromParent = None), childDone, childOutput, t, v, counter)
      case None => state

  def sendToChild_orRunChild_orBuffer(
      state: GroupByState,
      childDone: Sink[ChildDone],
      childOutput: Sink[U],
      t: T,
      v: V,
      counter: Long
  )(using OxUnsupervised): GroupByState =
    var s = state
    s.childMostRecentCounters.insert(v, counter) // Bump child counters.

    s.children.get(v) match
      case Some(child) => child.send(t)

      case None if s.children.size < parallelism =>
        // Starting a new child flow, running in the background; the child flow receives values via a channel,
        // and feeds its output to `childOutput`. Done signals are forwarded to `childDone`; elements & errors
        // are propagated to `childOutput`.
        val childChannel = BufferCapacity.newChannel[T]
        s = s.withChildAdded(v, childChannel)

        forkUnsupervised:
          childFlowTransform(v)(Flow.fromSource(childChannel))
            .onDone(childDone.sendOrClosed(ChildDone(v)).discard)
            // When the child flow is done, making sure that the source channel becomes closed as well
            // otherwise, we'd be risking a deadlock, if there are `childChannel.send`-s pending, and the
            // buffer is full; if the channel is already closed, this is a no-op.
            .onDone(childChannel.doneOrClosed().discard)
            .onError(t => childChannel.errorOrClosed(t).discard)
            .runPipeToSink(childOutput, propagateDone = false)
        .discard

        childChannel.send(t)

      case None =>
        assert(s.pendingFromParent == None)
        s = s.withPendingFromParent(t, v, counter)

        // Completing as done the child flow which didn't receive an element for the longest time. After
        // the flow completes, it will send `ChildDone` to `childDone`.
        s.childMostRecentCounters.extractMin().foreach((v, _) => s.children(v).done())
    end match

    s
  end sendToChild_orRunChild_orBuffer

  Flow.usingEmitInline: emit =>
    unsupervised:
      // Channel where all elements emitted by child flows will be sent; we use such a collective channel instead of
      // enumerating all child channels in the main `select`, as `select`s don't scale well with the number of
      // clauses. The elements from this channel are then emitted by the returned flow.
      val childOutput = BufferCapacity.newChannel[U]

      // Channel where completion of children is signalled (because the parent is complete, or the parallelism limit
      // is reached).
      val childDone = Channel.unlimited[ChildDone]

      // Parent channel, from which we receive as long as it's not done, and only when a child flow isn't pending
      // creation (see below). As the receive is conditional, the errors that occur on this channel are also
      // propagated to `childOutput`, which is always the first (priority) clause in the main `select`.
      case class FromParent(v: T)
      val parentChannel = parent.map(FromParent(_)).onError(childOutput.errorOrClosed(_).discard).runToChannel()

      var state = GroupByState(parentDone = false, None, Map.empty, 0L, WeightedHeap())

      // Main loop; while there are any values to receive (from parent or children).
      while state.elementsCanBeReceived do
        assert(state.children.size <= parallelism) // invariant

        // We do not receive from the parent when it's done, or when there's already a pending child flow to create
        // (but can't be created because of `parallelism` limit); we always receive from child output & child done
        // signals. In case of parent's error, the error is also propagated above to `childOutput`, so that it's
        // quickly received. Receiving from child output has priority over child done signals, to receive all child
        // values before marking a child as done.
        val pool =
          if state.shouldReceiveFromParentChannel
          then List(childOutput, childDone)
          else List(childOutput, childDone, parentChannel)

        selectOrClosed(pool) match
          case ChannelClosed.Done =>
            // Only the parent can be done; child completion is signalled via a value in `childDone`.
            state = state.withParentDone(isSourceDone(parentChannel))
            assert(state.parentDone)

            // Completing all children as done - there will be no more incoming values.
            List.unfold(0L)(_ => state.childMostRecentCounters.extractMin()).foreach(v => state.children(v).done())

          case e: ChannelClosed.Error => throw e.toThrowable

          case FromParent(t) =>
            state = state.withFromParentCounterIncremented
            state = sendToChild_orRunChild_orBuffer(state, childDone, childOutput, t, predicate(t), state.fromParentCounter)

          case ChildDone(v) =>
            state = state.withChildRemoved(v)

            // Children should only be done because their `childChannel` was completed as done by
            // `sendToChild_orRunChild_orBuffer`, then `childMostRecentCounters` should have `v` removed.
            // If it's still present, this indicates that the child flow was completed as done while the source
            // child channel is not, which is invalid usage.
            if state.childMostRecentCounters.contains(v) then
              throw new IllegalStateException(
                "Invalid usage of child flows: child flow was completed as done by user code (in " +
                  "childFlowTransform), while this is not allowed (see documentation for details)"
              )

            state = runChild_ifPending(state, childDone, childOutput)

          case u: U @unchecked => emit(u) // forwarding from `childOutput`
        end match
      end while
end groupByImpl
