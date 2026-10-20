package ox.flow.tracing

import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.{Span, SpanContext, SpanKind}
import ox.channels.BufferCapacity
import ox.flow.{Flow, FlowEmit}
import ox.telemetry.tracing.{OxTracing, SpanWithError}

private[ox] trait OxFlowTracing:
  self: OxTracing =>

  /** Returns a flow, which when run, evaluates `flow` within a new span, which is made a child of current span Span is ended when the flow
    * completes, fails or is interrupted. errors are recorded in span as events and via recordExceptions creates async boundary to isolate
    * downstream stages processing time from created span
    * @param beforeClose
    *   callback that will be executed right before closing a span
    */
  def span[T](flow: Flow[T])(
      spanName: String,
      spanKind: SpanKind = SpanKind.INTERNAL,
      attributes: Attributes = Attributes.empty(),
      links: Seq[SpanContext] = Seq.empty,
      beforeClose: SpanWithError => Span = _._1
  )(using BufferCapacity): Flow[T] =
    val spanned = Flow.usingEmitInline[T]: emit =>
      self.byNameSpan(flow.runToEmit(emit))(spanName, spanKind, attributes, links, beforeClose)
    spanned.buffer()
  end span

  /** As [[ox.flow.Flow.map]] but spans around execution of mapping function Span is made child of span which is current when executing a
    * flow
    */
  def mapSpanned[T, U](flow: Flow[T])(
      spanName: String,
      spanKind: SpanKind = SpanKind.INTERNAL,
      attributes: Attributes = Attributes.empty(),
      links: Seq[SpanContext] = Seq.empty,
      beforeClose: SpanWithError => Span = _._1
  )(f: T => U): Flow[U] =
    flow.map(t => self.byNameSpan(f(t))(spanName, spanKind, attributes, links, beforeClose))

  /** As [[ox.flow.Flow.flatMap]], but spans around execution of each flow returned by f spans opened within execution of f and of flow
    * returned by f are children of created span, f is called within the span, so if it throws, the exception is recorded on it
    */
  def flatMapSpanned[T, U](flow: Flow[T])(
      spanName: String,
      spanKind: SpanKind = SpanKind.INTERNAL,
      attributes: Attributes = Attributes.empty(),
      links: Seq[SpanContext] = Seq.empty,
      beforeClose: SpanWithError => Span = _._1
  )(f: T => Flow[U])(using BufferCapacity): Flow[U] =
    flow.flatMap(t => span(Flow.usingEmitInline[U](emit => f(t).runToEmit(emit)))(spanName, spanKind, attributes, links, beforeClose))

end OxFlowTracing
