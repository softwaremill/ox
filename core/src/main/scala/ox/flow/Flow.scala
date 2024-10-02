package ox.flow

import ox.channels.ChannelClosed
import ox.channels.Sink
import ox.channels.Source
import ox.repeatWhile

/** Describes an asynchronous transformation pipeline emitting elements of type `T`. */
class Flow[+T](protected val last: FlowStage[T]) extends FlowOps[T] with FlowRunOps[T] with FlowIOOps[T] with FlowTextOps[T]

object Flow extends FlowCompanionOps with FlowCompanionIOOps

//

trait FlowStage[+T]:
  def run(sink: FlowSink[T]): Unit

object FlowStage:
  def fromSource[T](source: Source[T]): FlowStage[T] =
    new FlowStage[T]:
      def run(next: FlowSink[T]): Unit = FlowSink.channelToSink(source, next)
end FlowStage

//

trait FlowSink[-T]:
  def onNext(t: T): Unit
  def onDone(): Unit
  def onError(e: Throwable): Unit

object FlowSink:
  /** Creates a sink which sends all elements to the given channel. Any closure events are propagated as well. */
  class ToChannel[T](ch: Sink[T]) extends FlowSink[T]:
    override def onNext(t: T): Unit = ch.send(t)
    override def onDone(): Unit = ch.done()
    override def onError(e: Throwable): Unit = ch.error(e)

  /** Creates a new sink which runs the provided callback when a new element is received. Closure events are propagated to the given sink.
    */
  inline def propagateClose[T](inline next: FlowSink[?])(inline runOnNext: T => Unit): FlowSink[T] =
    new FlowSink[T]:
      override def onNext(t: T): Unit = runOnNext(t)
      override def onDone(): Unit = next.onDone()
      override def onError(e: Throwable): Unit = next.onError(e)

  /** Propagates all elements and closure events to the given sink. */
  def channelToSink[T](source: Source[T], sink: FlowSink[T]): Unit =
    repeatWhile:
      val t = source.receiveOrClosed()
      t match
        case ChannelClosed.Done     => sink.onDone(); false
        case ChannelClosed.Error(r) => sink.onError(r); false
        case t: T @unchecked        => sink.onNext(t); true
end FlowSink
