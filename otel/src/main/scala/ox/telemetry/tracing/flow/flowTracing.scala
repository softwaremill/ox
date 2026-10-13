package ox.telemetry.tracing.flow

import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.{Span, SpanContext, SpanKind}
import ox.flow.Flow
import ox.telemetry.tracing.flow.OxFlowTracing

extension [T](flow: Flow[T])
  def span(oxTracing: OxFlowTracing)(
      spanName: String,
      spanKind: SpanKind = SpanKind.INTERNAL,
      attributes: Attributes = Attributes.empty(),
      links: Seq[SpanContext] = Seq.empty,
      beforeClose: ((Span, Option[Throwable])) => Span = _._1
  ): Flow[T] = oxTracing.span(flow)(spanName, spanKind, attributes, links, beforeClose)
end extension
