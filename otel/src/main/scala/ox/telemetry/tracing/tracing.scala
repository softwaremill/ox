package ox.telemetry.tracing;


import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.{Span, SpanContext, SpanKind}
import ox.flow.Flow

extension [T](flow: Flow[T])
  def span(oxTracing: OxTracing)(
    spanName: String,
    spanKind: SpanKind = SpanKind.INTERNAL,
    attributes: Attributes = Attributes.empty(),
    links: Seq[SpanContext] = Seq.empty,
    beforeClose: ((Span, Option[Throwable])) => Span = _._1
  ): Flow[T] = ???

extension [T](t: => T)
  def byNameSpan(oxTracing: OxTracing)(
    spanName: String,
    spanKind: SpanKind = SpanKind.INTERNAL,
    attributes: Attributes = Attributes.empty(),
    links: Seq[SpanContext] = Seq.empty,
    beforeClose: ((Span, Option[Throwable])) => Span = _._1
  ): T = ???  
  
  