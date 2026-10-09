package ox.flow.reactive

import ox.flow.Flow
import org.reactivestreams.Publisher
import ox.Ox
import ox.channels.BufferCapacity
import org.reactivestreams.FlowAdapters

extension [A](flow: Flow[A])
  /** This variant returns an implementation of `org.reactivestreams.Publisher`, as opposed to `java.util.concurrent.Flow.Publisher` which
    * is supported in the core module.
    *
    * @see
    *   [[Flow.toPublisher]]
    */
  def toReactiveStreamsPublisher(using Ox, BufferCapacity): Publisher[A] =
    FlowAdapters.toPublisher(flow.toPublisher)
end extension

object FlowReactiveStreams:
  /** This variant accepts an implementation of `org.reactivestreams.Publisher`, as opposed to `java.util.concurrent.Flow.Publisher` which
    * is supported in the core module.
    *
    * To release resources held by elements which are received but not emitted, use the overload with `onDiscard`.
    *
    * @see
    *   [[Flow.fromPublisher]]
    */
  def fromPublisher[T](p: Publisher[T])(using BufferCapacity): Flow[T] = Flow.fromPublisher(FlowAdapters.toFlowPublisher(p))

  /** This variant accepts an implementation of `org.reactivestreams.Publisher`, as opposed to `java.util.concurrent.Flow.Publisher` which
    * is supported in the core module.
    *
    * See the core overload with `onDiscard` for the callback's semantics.
    *
    * @see
    *   [[Flow.fromPublisher]]
    */
  def fromPublisher[T](p: Publisher[T], onDiscard: T => Unit)(using BufferCapacity): Flow[T] =
    Flow.fromPublisher(FlowAdapters.toFlowPublisher(p), onDiscard)
end FlowReactiveStreams
