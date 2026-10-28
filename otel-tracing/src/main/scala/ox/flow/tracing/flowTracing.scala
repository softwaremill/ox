package ox.flow.tracing

import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.{Span, SpanContext, SpanKind, Tracer}
import ox.flow.{Flow, FlowEmit}
import ox.otel.tracing.{SpanWithError, span}

extension [T](flow: Flow[T])
  /** Returns a flow, which for each element of this flow opens a new span. The span is made a child of the current span, and becomes the
    * current one until it ends, when the previously current span is restored.
    *
    * The span ends when the downstream stages are done processing the element, at the first asynchronous boundary (e.g. `buffer`), or if
    * any of the following stages throws.
    *
    * If a following stage throws, the span's status is set to `ERROR` and an appropriate event is added to it.
    *
    * @param beforeClose
    *   callback executed right before closing the span, which receives the span and the failure of the following stage, if any. If it
    *   throws, the exception is added as suppressed to the one thrown by the stage, or thrown if the stages succeeded.
    */
  def spanEach(tracer: Tracer)(
      spanName: String,
      spanKind: SpanKind = SpanKind.INTERNAL,
      attributes: Attributes = Attributes.empty(),
      links: Seq[SpanContext] = Seq.empty,
      beforeClose: SpanWithError[Nothing] => Span = _.span
  ): Flow[T] = Flow.usingEmitInline: emit =>
    flow.runToEmit(
      FlowEmit.fromInline: t =>
        span(tracer)(spanName, spanKind, attributes, links, beforeClose)(emit(t))
    )
end extension
