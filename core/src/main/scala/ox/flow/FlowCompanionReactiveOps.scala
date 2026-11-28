package ox.flow

import ox.channels.BufferCapacity
import ox.channels.Channel
import ox.channels.ChannelClosed
import ox.channels.toInt
import ox.discard
import ox.forkUnsupervised
import ox.repeatWhile
import ox.tapException
import ox.unsupervised

import java.util.concurrent.Flow.Publisher
import java.util.concurrent.Flow.Subscriber
import java.util.concurrent.Flow.Subscription
import java.util.concurrent.atomic.AtomicReference

trait FlowCompanionReactiveOps:
  this: Flow.type =>

  /** Same as the overload with `onDiscard`, except that elements received from the publisher, but not emitted, are dropped. */
  def fromPublisher[T](p: Publisher[T])(using BufferCapacity): Flow[T] = fromPublisher(p, (_: T) => ())

  /** Creates a [[Flow]] from a [[Publisher]], that is, which emits the elements received by subscribing to the publisher. A new
    * subscription is created every time this flow is run.
    *
    * The data is passed from a subscription to the flow using a [[ox.channel.Channel]], with a capacity given by the [[BufferCapacity]] in
    * scope. That's also how many elements will be at most requested from the publisher at a time.
    *
    * Every element received from the publisher is either emitted, or passed to `onDiscard` exactly once. Elements are discarded when the
    * publisher or the flow fails, is interrupted or stops early (e.g. due to `take`), including elements delivered by the publisher after
    * the subscription is cancelled. Use this to release resources held by the elements (e.g. pooled buffers).
    *
    * `onDiscard` may be called concurrently, from the publisher's threads and from the thread running the flow, so it must be thread-safe.
    * If it throws while the flow ends, the remaining elements are still discarded, and the exception is added as suppressed to the one
    * which ended the flow.
    *
    * The publisher parameter should implement the JDK 9+ `Flow.Publisher` API. To create a flow from a publisher implementing
    * `com.reactivestreams.Publisher`, use the `flow-reactive-streams` module.
    */
  def fromPublisher[T](p: Publisher[T], onDiscard: T => Unit)(using BufferCapacity): Flow[T] = usingEmitInline: emit =>
    // using an unsafe scope for efficiency
    unsupervised {
      val channel = BufferCapacity.newChannel[T]
      val capacity = summon[BufferCapacity].toInt
      val demandThreshold = math.ceil(capacity / 2.0).toInt

      // used to "extract" the subscription that is set in the subscription running in a fork
      val state = new AtomicReference[SubscriptionState](SubscriptionState.NotSubscribed)
      var subscription: Subscription = null
      // the error is stored, instead of erroring the channel, so that buffered elements can be received and discarded
      val publisherError = new AtomicReference[Option[Throwable]](None)

      var toDemand = 0

      {
        // unsafe, but we are sure that this won't throw any exceptions (unless there's a bug in the publisher)
        forkUnsupervised {
          p.subscribe(new Subscriber[T]:
            def onSubscribe(s: Subscription): Unit =
              val previous = state.getAndUpdate:
                case SubscriptionState.NotSubscribed => SubscriptionState.Subscribed(s)
                case other                           => other
              // the flow might have been cancelled before the subscription was set; a second subscription must be cancelled (rule 2.5)
              if previous == SubscriptionState.NotSubscribed then s.request(capacity) else s.cancel()

            def onNext(t: T): Unit =
              // an interrupted send doesn't add the element to the channel; this only happens if the publisher ignores demand
              try
                channel.sendOrClosed(t) match
                  case _: ChannelClosed => onDiscard(t)
                  case _                => ()
              catch
                case e: InterruptedException =>
                  onDiscard(t)
                  throw e

            def onError(t: Throwable): Unit =
              // set before closing the channel, so that it's visible when the consumer receives Done
              publisherError.set(Some(t))
              channel.doneOrClosed().discard

            def onComplete(): Unit = channel.doneOrClosed().discard)
        }.discard

        repeatWhile:
          val t = channel.receiveOrClosed()
          t match
            case ChannelClosed.Done =>
              publisherError.get().foreach(e => throw e)
              false
            case e: ChannelClosed.Error => throw e.toThrowable
            case t: T @unchecked        =>
              // fail fast: buffered elements aren't emitted after a publisher error
              publisherError.get() match
                case Some(e) =>
                  try onDiscard(t)
                  catch case de: Throwable => e.addSuppressed(de)
                  throw e
                case None => ()

              emit(t)

              // if we have an element, onSubscribe must have already happened; we can read the subscription and cache it for later
              if subscription == null then
                subscription = state.get() match
                  case SubscriptionState.Subscribed(s) => s
                  case other                           => throw new IllegalStateException(s"Unexpected subscription state: $other")

              // now that we'ver received an element from the channel, we can request more
              toDemand += 1
              // we request in batches, to avoid too many requests
              if toDemand >= demandThreshold then
                subscription.request(toDemand)
                toDemand = 0

              true
          end match
        // exceptions might be propagated from the publisher, from emit, but they might also originate from an interruption
      }.tapException: _ =>
        state.getAndSet(SubscriptionState.Cancelled) match
          case SubscriptionState.Subscribed(s) => s.cancel()
          case _                               => ()
        channel.doneOrClosed().discard
        discardBuffered(channel, onDiscard)
    }
  end fromPublisher
end FlowCompanionReactiveOps

private enum SubscriptionState:
  case NotSubscribed
  case Subscribed(s: Subscription)
  case Cancelled

/** Passes all elements remaining in a done `channel` to `onDiscard`. Interruptions are deferred until the channel is drained, so that no
  * element is lost. If `onDiscard` throws, draining continues, and the first exception is rethrown at the end, with the others suppressed.
  */
private def discardBuffered[T](channel: Channel[T], onDiscard: T => Unit): Unit =
  var interrupted = false
  var discardError: Throwable = null
  var draining = true
  while draining do
    try
      channel.receiveOrClosed() match
        case _: ChannelClosed => draining = false
        case t: T @unchecked  =>
          try onDiscard(t)
          catch
            case e: Throwable =>
              if discardError == null then discardError = e else discardError.addSuppressed(e)
    catch case _: InterruptedException => interrupted = true
  end while
  if interrupted then Thread.currentThread().interrupt()
  if discardError != null then throw discardError
end discardBuffered
