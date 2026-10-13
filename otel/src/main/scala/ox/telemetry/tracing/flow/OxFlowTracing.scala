package ox.telemetry.tracing.flow

import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.{Span, SpanContext, SpanKind}
import ox.flow.Flow
import ox.telemetry.tracing.OxTracing

private[tracing] trait OxFlowTracing:
  self: OxTracing =>
  def span[T](flow: Flow[T])(
      spanName: String,
      spanKind: SpanKind = SpanKind.INTERNAL,
      attributes: Attributes = Attributes.empty(),
      links: Seq[SpanContext] = Seq.empty,
      beforeClose: ((Span, Option[Throwable])) => Span = _._1
  ): Flow[T] = Flow.usingEmit: emit =>
    self.byNameSpan(
      flow.runToEmit(
        emit
      )
    )(spanName, spanKind, attributes, links, beforeClose)
