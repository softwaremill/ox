package ox.flow

import ox.Ox
import ox.channels.ChannelClosed
import ox.channels.Sink
import ox.channels.Source
import ox.channels.StageCapacity
import ox.discard
import ox.repeatWhile

import ox.channels.forkPropagate
import ox.supervised

class Flow[T](last: FlowStage[T]):
  def map[U](f: T => U): Flow[U] = addTransformSinkStage(next => FlowSink.propagateClose(next)(t => next.onNext(f(t))))
  def filter(f: T => Boolean): Flow[T] = addTransformSinkStage(next => FlowSink.propagateClose(next)(t => if f(t) then next.onNext(t)))

  def async()(using StageCapacity): Flow[T] =
    Flow(
      new FlowStage:
        override def run(next: FlowSink[T])(using Ox): Unit =
          val ch = StageCapacity.newChannel[T]
          runLastToChannelAsync(ch)
          FlowStage.FromChannel(ch).run(next)
    )

  //

  def foreach(sink: T => Unit): Unit = supervised:
    last.run(new FlowSink[T]:
      override def onNext(t: T): Unit = sink(t)
      override def onDone(): Unit = () // ignore
      override def onError(e: Throwable): Unit = throw e
    )

  def run()(using Ox, StageCapacity): Source[T] =
    val ch = StageCapacity.newChannel[T]
    runLastToChannelAsync(ch)
    ch

  //

  private def runLastToChannelAsync(ch: Sink[T])(using Ox): Unit =
    forkPropagate(ch)(last.run(FlowSink.ToChannel(ch))).discard

  private inline def addTransformSinkStage[U](inline doTransform: FlowSink[U] => FlowSink[T]): Flow[U] =
    Flow(
      new FlowStage:
        override def run(next: FlowSink[U])(using Ox): Unit =
          last.run(doTransform(next))
    )

object Flow:
  def fromSource[T](source: Source[T]): Flow[T] =
    Flow(FlowStage.FromChannel(source))

  def fromSender[T](withSender: FlowSender[T] => Unit): Flow[T] =
    Flow(FlowStage.FromSender(withSender))

//

// a simplified sink used in .fromSender
trait FlowSender[T]:
  def apply(t: T): Unit

//

trait FlowStage[T]:
  def run(next: FlowSink[T])(using Ox): Unit

object FlowStage:
  class FromChannel[T](ch: Source[T]) extends FlowStage[T]:
    def run(next: FlowSink[T])(using Ox): Unit =
      repeatWhile:
        val t = ch.receiveOrClosed()
        t match
          case ChannelClosed.Done     => next.onDone(); false
          case ChannelClosed.Error(r) => next.onError(r); false
          case t: T @unchecked        => next.onNext(t); true

  class FromSender[T](withSender: FlowSender[T] => Unit) extends FlowStage[T]:
    def run(next: FlowSink[T])(using Ox): Unit =
      withSender(
        new FlowSender[T]:
          def apply(t: T): Unit = next.onNext(t)
      )
      next.onDone()

//

trait FlowSink[T]:
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
  inline def propagateClose[T](inline next: FlowSink[_])(inline runOnNext: T => Unit): FlowSink[T] =
    new FlowSink[T]:
      override def onNext(t: T): Unit = runOnNext(t)
      override def onDone(): Unit = next.onDone()
      override def onError(e: Throwable): Unit = next.onError(e)
