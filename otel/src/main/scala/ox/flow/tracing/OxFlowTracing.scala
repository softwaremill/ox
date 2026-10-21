package ox.flow.tracing

import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.{Span, SpanContext, SpanKind}
import ox.channels.BufferCapacity
import ox.flow.Flow
import ox.telemetry.tracing.{OxTracing, SpanWithError}

private[ox] trait OxFlowTracing:
  self: OxTracing =>

  /** Runs each element of `flow` through the stages added by `f` within its own span: from the element entering the stages, to the last
    * stage finishing the processing of everything derived from it. Elements are processed one after another. `f` is called within the span
    * of each element, so spans opened by its body are children of that span, and if it throws, the exception is recorded on it. Stages
    * created by `f`, including async ones (e.g. `buffer`), are covered by the span. The span is ended when the stages complete, fail or are
    * interrupted; errors are recorded in it as events.
    *
    * The span is separated from the stages placed after `spanStages` by an async boundary, so their processing time is counted only as far
    * as the `BufferCapacity` allows.
    *
    * @param beforeClose
    *   callback that will be executed right before closing a span
    */
  def spanStages[T, U](flow: Flow[T])(
      spanName: String,
      spanKind: SpanKind = SpanKind.INTERNAL,
      attributes: Attributes = Attributes.empty(),
      links: Seq[SpanContext] = Seq.empty,
      beforeClose: SpanWithError => Span = _._1
  )(f: Flow[T] => Flow[U])(using BufferCapacity): Flow[U] =
    flow.flatMap: t =>
      val spanned = Flow.usingEmitInline[U]: emit =>
        self.byNameSpan(f(Flow.fromValues(t)).runToEmit(emit))(spanName, spanKind, attributes, links, beforeClose)
      spanned.buffer()

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

end OxFlowTracing
