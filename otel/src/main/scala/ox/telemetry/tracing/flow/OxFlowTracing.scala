package ox.telemetry.tracing.flow

import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.{Span, SpanContext, SpanKind}
import ox.flow.Flow
import ox.telemetry.tracing.OxTracing

private[tracing] trait OxFlowTracing:
  self: OxTracing =>

  /** Returns a flow, which when run, evaluates `flow` within a new span. The span is opened when the flow is run, as a child of the span current at that point. It is ended when the flow completes, fails or is interrupted. Exceptions are recorded on the span as
    * in [[OxTracing.byNameSpan]].
    *
    * The parameters are the same as for [[OxTracing.byNameSpan]].
    */
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
end OxFlowTracing
