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
