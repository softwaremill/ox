package ox.flow

import ox.channels.BufferCapacity
import ox.channels.ChannelClosed
import ox.channels.toInt
import ox.discard
import ox.forkUnsupervised
import ox.pipe
import ox.repeatWhile
import ox.tapException
import ox.unsupervised

import java.util.concurrent.Flow.Publisher
import java.util.concurrent.Flow.Subscriber
import java.util.concurrent.Flow.Subscription
import java.util.concurrent.atomic.AtomicReference

trait FlowCompanionReactiveOps:
  this: Flow.type =>

  /** Creates a [[Flow]] from a [[Publisher]], that is, which emits the elements received by subscribing to the publisher. A new
    * subscription is created every time this flow is run.
    *
    * The data is passed from a subscription to the flow using a [[ox.channel.Channel]], with a capacity given by the [[BufferCapacity]] in
    * scope. That's also how many elements will be at most requested from the publisher at a time.
    *
    * The publisher parameter should implement the JDK 9+ `Flow.Publisher` API. To create a flow from a publisher implementing
    * `com.reactivestreams.Publisher`, use the `flow-reactive-streams` module.
    */
  def fromPublisher[T](p: Publisher[T])(using BufferCapacity): Flow[T] = usingEmitInline: emit =>
    // using an unsafe scope for efficiency
    unsupervised {
      val channel = BufferCapacity.newChannel[T]
      val capacity = summon[BufferCapacity].toInt
      val demandThreshold = math.ceil(capacity / 2.0).toInt

      // used to "extract" the subscription that is set in the subscription running in a fork
      val subscriptionRef = new AtomicReference[Subscription]()
      var subscription: Subscription = null

      var toDemand = 0

      {
        // unsafe, but we are sure that this won't throw any exceptions (unless there's a bug in the publisher)
        forkUnsupervised {
          p.subscribe(new Subscriber[T]:
            def onSubscribe(s: Subscription): Unit =
              subscriptionRef.set(s)
              s.request(capacity)

            def onNext(t: T): Unit = channel.send(t)
            def onError(t: Throwable): Unit = channel.error(t)
            def onComplete(): Unit = channel.done()
          )
        }.discard

        repeatWhile:
          val t = channel.receiveOrClosed()
          t match
            case ChannelClosed.Done     => false
            case e: ChannelClosed.Error => throw e.toThrowable
            case t: T @unchecked =>
              emit(t)

              // if we have an element, onSubscribe must have already happened; we can read the subscription and cache it for later
              if subscription == null then subscription = subscriptionRef.get()

              // now that we'ver received an element from the channel, we can request more
              toDemand += 1
              // we request in batches, to avoid too many requests
              if toDemand >= demandThreshold then
                subscription.request(toDemand)
                toDemand = 0

              true
          end match
        // exceptions might be propagated from the channel, but they might also originate from an interruption
      }.tapException(_ => subscriptionRef.get().pipe(s => if s != null then s.cancel()))
    }
end FlowCompanionReactiveOps
