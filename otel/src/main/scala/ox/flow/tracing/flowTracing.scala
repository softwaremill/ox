package ox.flow.tracing

import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.{Span, SpanContext, SpanKind, Tracer}
import ox.channels.BufferCapacity
import ox.flow.{Flow, FlowEmit}
import ox.telemetry.tracing.{SpanWithError, span}

extension [T](flow: Flow[T])
  /** Runs each element of this flow through the stages added by `f`, within its own span. The span is made a child of the current span, and
    * becomes the current one until it ends, when the previously current span is restored. Elements are processed one after another.
    *
    * The span is opened when the element enters the stages, and ends when the last stage created by `f` is done processing everything
    * derived from the element. `f` is called within the span, so spans opened by it and by the stages are children of the span.
    *
    * The span is separated from the following stages by an async boundary, so their processing time is not counted, as long as the stages
    * emit at most `BufferCapacity + 1` elements for one element of this flow. Beyond that, the span also includes the time of waiting for
    * the following stages to free up space in the buffer.
    *
    * If `f` or any of the stages throws, the span's status is set to `ERROR` and an appropriate event is added to it.
    *
    * @param beforeClose
    *   callback executed right before closing the span, which receives the span and the failure of the stages, if any. If it throws, the
    *   exception is added as suppressed to the one thrown by the stages, or thrown if the stages succeeded.
    */
  def spanStages[U](tracer: Tracer)(
      spanName: String,
      spanKind: SpanKind = SpanKind.INTERNAL,
      attributes: Attributes = Attributes.empty(),
      links: Seq[SpanContext] = Seq.empty,
      beforeClose: SpanWithError[Nothing] => Span = _.span
  )(f: Flow[T] => Flow[U])(using BufferCapacity): Flow[U] =
    flow.flatMap: t =>
      val spanned = Flow.usingEmitInline[U]: emit =>
        f(Flow.fromValues(t)).runToEmit(emit).span(tracer)(spanName, spanKind, attributes, links, beforeClose)
      spanned.buffer()

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
        span(emit(t))(tracer)(spanName, spanKind, attributes, links, beforeClose)
    )
end extension
