package ox.flow.tracing

import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.{Span, SpanContext, SpanKind}
import ox.channels.BufferCapacity
import ox.flow.Flow
import ox.telemetry.tracing.SpanWithError

extension [T](flow: Flow[T])
  /** Opens a span around the run of this flow, see [[ox.flow.tracing.OxFlowTracing.span]].
    */
  def span(oxTracing: OxFlowTracing)(
      spanName: String,
      spanKind: SpanKind = SpanKind.INTERNAL,
      attributes: Attributes = Attributes.empty(),
      links: Seq[SpanContext] = Seq.empty,
      beforeClose: SpanWithError => Span = _.span
  )(using BufferCapacity): Flow[T] = oxTracing.span(flow)(spanName, spanKind, attributes, links, beforeClose)

  /** As [[ox.flow.Flow.map]], with each call of `f` within a span, see [[ox.flow.tracing.OxFlowTracing.mapSpanned]]. */
  def mapSpanned[U](oxTracing: OxFlowTracing)(
      spanName: String,
      spanKind: SpanKind = SpanKind.INTERNAL,
      attributes: Attributes = Attributes.empty(),
      links: Seq[SpanContext] = Seq.empty,
      beforeClose: SpanWithError => Span = _.span
  )(f: T => U): Flow[U] = oxTracing.mapSpanned(flow)(spanName, spanKind, attributes, links, beforeClose)(f)

  /** As [[ox.flow.Flow.flatMap]], with each flow returned by `f` run within a span, see [[ox.flow.tracing.OxFlowTracing.flatMapSpanned]].
    */
  def flatMapSpanned[U](oxTracing: OxFlowTracing)(
      spanName: String,
      spanKind: SpanKind = SpanKind.INTERNAL,
      attributes: Attributes = Attributes.empty(),
      links: Seq[SpanContext] = Seq.empty,
      beforeClose: SpanWithError => Span = _.span
  )(f: T => Flow[U])(using BufferCapacity): Flow[U] =
    oxTracing.flatMapSpanned(flow)(spanName, spanKind, attributes, links, beforeClose)(f)
end extension
