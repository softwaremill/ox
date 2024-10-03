package ox.flow

import ox.channels.ChannelClosed
import ox.channels.Source
import ox.repeatWhile

/** Describes an asynchronous transformation pipeline emitting elements of type `T`. */
class Flow[+T](protected val last: FlowStage[T]) extends FlowOps[T] with FlowRunOps[T] with FlowIOOps[T] with FlowTextOps[T]

object Flow extends FlowCompanionOps with FlowCompanionIOOps

//

trait FlowStage[+T]:
  def run(sink: FlowSink[T]): Unit

//

trait FlowSink[-T]:
  def apply(t: T): Unit

object FlowSink:
  private[flow] inline def fromInline[T](inline f: T => Unit): FlowSink[T] =
    new FlowSink[T]:
      def apply(t: T): Unit = f(t)

  /** Propagates all elements and closure events to the given sink. */
  def channelToSink[T](source: Source[T], sink: FlowSink[T]): Unit =
    repeatWhile:
      val t = source.receiveOrClosed()
      t match
        case ChannelClosed.Done     => false
        case ChannelClosed.Error(r) => throw r
        case t: T @unchecked        => sink(t); true
end FlowSink
