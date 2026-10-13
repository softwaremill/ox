package ox.telemetry.tracing

import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.{Span, SpanContext, SpanKind}
import ox.telemetry.tracing.OxTracing

extension [T](t: => T)
  def byNameSpan(oxTracing: OxTracing)(
      spanName: String,
      spanKind: SpanKind = SpanKind.INTERNAL,
      attributes: Attributes = Attributes.empty(),
      links: Seq[SpanContext] = Seq.empty,
      beforeClose: ((Span, Option[Throwable])) => Span = _._1
  ): T = oxTracing.byNameSpan(t)(spanName, spanKind, attributes, links, beforeClose)
end extension

export ox.telemetry.tracing.flow.span

def currentSpanUnsafe(oxTracing: OxTracing) = oxTracing.currentSpanUnsafe()

def spanUnsafe(oxTracing: OxTracing)(
    spanName: String,
    spanKind: SpanKind = SpanKind.INTERNAL,
    attributes: Attributes = Attributes.empty(),
    links: Seq[SpanContext] = Seq.empty
): AutoCloseableSpan = oxTracing.spanUnsafe(spanName, spanKind, attributes, links)
