package ox.telemetry.tracing.flow

import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.{Span, SpanContext, SpanKind}
import ox.flow.Flow
import ox.telemetry.tracing.internal.OxTracingBase

private[tracing] trait OxFlowTracing:
  extension [T](flow: Flow[T])
    def span(
        spanName: String,
        spanKind: SpanKind = SpanKind.INTERNAL,
        attributes: Attributes = Attributes.empty(),
        links: Seq[SpanContext] = Seq.empty,
        beforeClose: ((Span, Option[Throwable])) => Span = _._1
    ): Flow[T]
  end extension
end OxFlowTracing

private[tracing] object OxFlowTracing:
  private[tracing] final class OxFlowTracingImpl(base: OxTracingBase) extends OxFlowTracing:
    extension [T](flow: Flow[T])
      override def span(
          spanName: String,
          spanKind: SpanKind,
          attributes: Attributes,
          links: Seq[SpanContext],
          beforeClose: ((Span, Option[Throwable])) => Span
      ): Flow[T] =
        Flow.usingEmit: emit =>
          base.byNameSpan(
            flow.runToEmit(
              emit
            )
          )(spanName, spanKind, attributes, links, beforeClose)
    end extension
  end OxFlowTracingImpl
end OxFlowTracing
