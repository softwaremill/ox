package ox.flow

import ox.Ox
import ox.channels.BufferCapacity
import ox.channels.Channel
import ox.channels.ChannelClosed
import ox.channels.Sink
import ox.channels.forkPropagate
import ox.channels.selectOrClosed
import ox.discard
import ox.fork
import ox.forkDiscard

import java.util.concurrent.Flow.Publisher
import java.util.concurrent.Flow.Subscriber
import java.util.concurrent.Flow.Subscription
import ox.unsupervised
import ox.externalRunner

trait FlowReactiveOps[+T]:
  outer: Flow[T] =>

  /** Converts this [[Flow]] into a [[Publisher]]. The flow is run every time the publisher is subscribed to.
    *
    * Must be run within a concurrency scope, as upon subscribing, a fork is created to run the publishing process. Hence, the scope should
    * remain active as long as the publisher is used.
    *
    * Elements emitted by the flow are buffered, using a buffer of capacity given by the [[BufferCapacity]] in scope.
    *
    * The returned publisher is from the JDK 9+ `Flow.Publisher` API. To obtain a publisher implementing `com.reactivestreams.Publisher`,
    * use the `flow-reactive-streams` module.
    */
  def toPublisher[U >: T](using Ox, BufferCapacity): Publisher[U] =
    // we need to obtain the external runner while on a fork managed by Ox
    val external = externalRunner()

    new Publisher[U]:
      // 1.10: subscribe can be called multiple times; each time, the flow is started from scratch
      // 1.11: subscriptions are unicast
      def subscribe(subscriber: Subscriber[? >: U]): Unit =
        if subscriber == null then throw new NullPointerException("1.9: subscriber is null")
        // 3.13: the reference to the subscriber is held only as long as the main loop below runs
        // 3.14: not in this implementation

        // `runToSubscriber` blocks as long as data is produced by the flow or until the subscription is cancelled
        // we cannot block `subscribe` (see https://github.com/reactive-streams/reactive-streams-jvm/issues/393),
        // hence running in a fork; however, the reactive library might run .subscribe on a different thread, that's
        // why we need to use the external runner functionality
        external.runAsync(forkDiscard(runToSubscriber(subscriber)).discard)
      end subscribe
    end new
  end toPublisher

  private def runToSubscriber[U >: T](subscriber: Subscriber[U])(using BufferCapacity): Unit =
    // starting a new scope so that cancelling (== completing the main body) cleans up (interrupts) any background forks
    // using an unsafe scope for efficiency, we only ever start a single fork where all errors are propagated
    unsupervised:
      val signals = Channel.unlimited[Signal]
      // 1.9: onSubscribe must be called first
      subscriber.onSubscribe(new FlowSubscription(signals))

      // we need separate error & data channels so that we can select from error & signals only, without receiving data
      // 1.4 any errors from running the flow end up here
      val errors = Channel.unlimited[Nothing]
      val data = BufferCapacity.newChannel[T]

      // running the flow in the background; all errors end up as an error of the `errors` channel
      forkPropagate(errors) {
        last.run(FlowEmit.fromInline(t => data.send(t)))
        data.done()
      }.discard

      // processing state: cancelled flag + demand
      var cancelled = false
      var demand = 0L

      def cancel() = cancelled = true
      def signalErrorAndCancel(e: Throwable): Unit =
        if !cancelled then
          cancel()
          subscriber.onError(e)

      def increaseDemand(d: Long): Unit =
        if d <= 0 then signalErrorAndCancel(new IllegalArgumentException("3.9: demand must be positive"))
        else
          demand += d
          // 3.17: when demand overflows `Long.MaxValue`, this is treated as the signalled demand to be "effectively unbounded"
          if demand < 0 then demand = Long.MaxValue

      // main processing loop: running as long as
      while !cancelled do // 1.7, 3.12 - ending the main loop after onCompelte/onError
        if demand == 0 then
          selectOrClosed(errors.receiveClause, signals.receiveClause) match
            case signals.Received(Signal.Request(n)) => increaseDemand(n)
            case signals.Received(Signal.Cancel)     => cancel()
            case errors.Received(_)                  => // impossible
            case ChannelClosed.Done                  => // impossible
            case ChannelClosed.Error(e) => // only `errors` can be closed due to an error
              cancel()
              subscriber.onError(e)
        else
          selectOrClosed(errors.receiveClause, signals.receiveClause, data.receiveClause) match
            case signals.Received(Signal.Request(n)) => increaseDemand(n)
            case signals.Received(Signal.Cancel)     => cancel()
            case errors.Received(_)                  => // impossible
            case data.Received(t: T) =>
              subscriber.onNext(t)
              demand -= 1
            case ChannelClosed.Done => // only `data` can be done
              cancel() // 1.6: when signalling onComplete/onError, the subscription is considered cancelled
              subscriber.onComplete() // 1.5
            case ChannelClosed.Error(e) => // only `errors` can be closed due to an error
              cancel()
              subscriber.onError(e)
      end while
  end runToSubscriber

end FlowReactiveOps

/** Signals sent from a [[Subscription]] to a running [[Publisher]]. */
private enum Signal:
  case Request(n: Long)
  case Cancel

private class FlowSubscription(signals: Sink[Signal]) extends Subscription:
  // 3.2, 3.4: request/cancel can be called anytime, in a thread-safe way
  // 3.3: there's no recursion between request & onNext
  // 3.6: after a cancel, more requests can be sent to the channel, but they won't be processed (the cancel will be processed first)
  // 3.15: the signals channel is never closed
  def request(n: Long): Unit = signals.send(Signal.Request(n))
  // 3.5: as above for 3.2
  // 3.7: as above for 3.6
  // 3.16: as above for 3.15
  def cancel(): Unit = signals.send(Signal.Cancel)

  // 3.10, 3.11: no synchronous calls in this implementation
end FlowSubscription

private trait ExternalScheduler:
  def run(f: () => Unit): Unit
