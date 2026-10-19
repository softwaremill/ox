package ox.flow.tracing

import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.{Span, SpanContext, SpanKind}
import io.opentelemetry.context.Context
import ox.*
import ox.channels.BufferCapacity
import ox.flow.{Flow, FlowEmit}
import ox.telemetry.tracing.{OxTracing, SpanWithError}

private[ox] trait OxFlowTracing:
  self: OxTracing =>

  /** Returns a flow, which when run, evaluates `flow` within a new span. The span is opened when the flow is run, as a child of the span
   * current at that point. It is ended when the flow completes, fails or is interrupted. Exceptions are recorded on the span as in
   * [[OxTracing.byNameSpan]].
   *
   * The span covers the whole execution of the flow: from when it is run until it completes. This includes the stages placed after
   * `spanned` that run synchronously (e.g. `map`, `flatMap`), as they are called from within the span: their duration is counted, spans
   * created there are children of this span, and if one of them fails, so does the span. Stages after an asynchronous boundary (e.g.
   * `buffer`) run concurrently, so they don't extend the span, which ends once its upstream completes.
   *
   * The parameters are the same as for [[OxTracing.byNameSpan]].
   *
   *    * NOT OK Span time depends of donstream stages
   *    * NOT OK Spanned stages dont follow code structure
   */
  def spanned[T](flow: Flow[T])(
    spanName: String,
    spanKind: SpanKind = SpanKind.INTERNAL,
    attributes: Attributes = Attributes.empty(),
    links: Seq[SpanContext] = Seq.empty,
    beforeClose: SpanWithError => Span = _._1
  ): Flow[T] = Flow.usingEmitInline: emit =>
    self.byNameSpan(flow.runToEmit(emit))(spanName, spanKind, attributes, links, beforeClose)

  /** As [[spanned]], but the stages placed after it are isolated from the span:
    *   - they are run with the context that was current before the span was opened, so spans created there are not children of this
    *     span, but siblings of it,
    *   - if one of them fails, the exception is not recorded on the span and doesn't set its status; it is still thrown through the span.
    *
    * Their duration is still counted in the span, if they run synchronously.
    *
    * The parameters are the same as for [[OxTracing.byNameSpan]].
   *
   * NOT OK Span time depends of donstream stages
   * NOT OK Spanned stages dont follow code structure
    */
  def spannedIsolated[T](flow: Flow[T])(
      spanName: String,
      spanKind: SpanKind = SpanKind.INTERNAL,
      attributes: Attributes = Attributes.empty(),
      links: Seq[SpanContext] = Seq.empty,
      beforeClose: SpanWithError => Span = _._1
  ): Flow[T] = Flow.usingEmitInline: emit =>
    val parent = Context.current()
    var downstreamError: Throwable | Null = null
    self.byNameSpanIgnoring(
      flow.runToEmit(
        FlowEmit.fromInline: t =>
          val scope = parent.makeCurrent()
          try emit(t)
          catch
            case e: Throwable =>
              downstreamError = e
              throw e
          finally scope.close()
      )
    )(_ eq downstreamError)(spanName, spanKind, attributes, links, beforeClose)

  /** As [[spanned]], but the flow is run asynchronously: `flow` and the span run in a fork, and the elements are passed to the stages
    * placed after it through a buffer, of the size given by the [[BufferCapacity]] in scope.
    *
    * Because of that boundary, the span doesn't depend on which stages follow it:
    *   - it covers `flow` and everything upstream of it, from when the flow is run until its last element is passed to the buffer, or it
    *     fails; the duration of the stages placed after it is not counted (except through backpressure, once the buffer is full),
    *   - the stages placed after it run with the context of the caller, so spans created there are not children of this span,
    *   - exceptions thrown by them are not recorded on the span.
    *
    * The price is a change of how the flow is run: `flow` runs in a fork, up to the buffer size ahead of the downstream stages, and
    * `beforeClose` is called on the fork's thread.
    *
    * The parameters are the same as for [[OxTracing.byNameSpan]].
   *
   *  OK - spanned stages follows code structure
   *  OK - span time is isolated from downstream stages
    */
  def spannedAsync[T](flow: Flow[T])(
      spanName: String,
      spanKind: SpanKind = SpanKind.INTERNAL,
      attributes: Attributes = Attributes.empty(),
      links: Seq[SpanContext] = Seq.empty,
      beforeClose: SpanWithError => Span = _._1
  )(using BufferCapacity): Flow[T] =
    spanned(flow)(spanName, spanKind, attributes, links, beforeClose).buffer()

  /** Applies `stages` once to a flow of the elements of `flow`, and opens a new span for each element, from when it enters the `stages`
    * until the `stages` are done processing it. The stages can be stateful, and can conflate or extrapolate the elements, as they are not
    * restarted for each element. The span of an element ends when its processing returns, so:
    *   - if the `stages` emit several elements for an element (e.g. `flatMap`), the span covers all of them,
    *   - if the `stages` hold an element back (e.g. `grouped`), the span covers only taking it in, and the element which completes a group
    *     is also the one whose span covers emitting the group.
    *
    * Spans created by the `stages` are children of the span of the element being processed. `flow` is run separately of the spans, so
    * the time it takes to produce the elements is not counted, and spans created by `flow` are not their children. The result of the
    * `stages` is passed to the stages placed after `spanEachStages` through a buffer, of the size given by the [[BufferCapacity]] in
    * scope, so they are not counted either, and their spans are not children of the span. The flow can be infinite.
    *
    * If the `stages` contain an asynchronous stage (e.g. `buffer`, `mapPar`), the span of an element ends once it is passed to it.
    *
    * The parameters are the same as for [[OxTracing.byNameSpan]].
    */
  def spanEachStages[T, U](flow: Flow[T])(
      spanName: String,
      spanKind: SpanKind = SpanKind.INTERNAL,
      attributes: Attributes = Attributes.empty(),
      links: Seq[SpanContext] = Seq.empty,
      beforeClose: SpanWithError => Span = _._1
  )(stagesToSpan: Flow[T] => Flow[U])(using BufferCapacity): Flow[U] =
    stagesToSpan(spanEachDownstream(flow)(spanName, spanKind, attributes, links, beforeClose)).buffer()

  /** As [[ox.flow.Flow.map]], but each call of `f` is run within a new span, which is ended once `f` completes. Only `f` is covered:
    * the result is emitted to the stages placed after it when the span is already closed, so they are neither counted nor children of
    * the span. Spans created by `f` are children of the span, and if `f` throws, the exception is recorded on it.
    *
    * The parameters are the same as for [[OxTracing.byNameSpan]].
    */
  def mapSpanned[T, U](flow: Flow[T])(
      spanName: String,
      spanKind: SpanKind = SpanKind.INTERNAL,
      attributes: Attributes = Attributes.empty(),
      links: Seq[SpanContext] = Seq.empty,
      beforeClose: SpanWithError => Span = _._1
  )(f: T => U): Flow[U] =
    flow.map(t => self.byNameSpan(f(t))(spanName, spanKind, attributes, links, beforeClose))

  /** As [[ox.flow.Flow.flatMap]], but the flow returned by each call of `f` is run within a new span, as with [[spannedAsync]]: each such
    * flow is run in a fork, and its elements are passed to the stages placed after `flatMapSpanned` through a buffer, of the size given
    * by the [[BufferCapacity]] in scope. Because of that boundary, the span doesn't depend on the stages that follow: it covers the flow
    * returned by `f`, from when it is run until its last element is passed to the buffer, spans created by the flow are children of the
    * span, and the stages placed after are not counted, are not children of it, and their failures are not recorded on it. The outputs
    * are emitted lazily, so the flow can be large or infinite.
    *
    * The parameters are the same as for [[OxTracing.byNameSpan]].
    */
  def flatMapSpanned[T, U](flow: Flow[T])(
      spanName: String,
      spanKind: SpanKind = SpanKind.INTERNAL,
      attributes: Attributes = Attributes.empty(),
      links: Seq[SpanContext] = Seq.empty,
      beforeClose: SpanWithError => Span = _._1
  )(f: T => Flow[U])(using BufferCapacity): Flow[U] =
    flow.flatMap(t => spannedAsync(f(t))(spanName, spanKind, attributes, links, beforeClose))

  /** Returns a flow, which for each element of `flow` opens a new span, emits the element to the stages placed after it, and ends the span
    * once they are done with it. The span is an entry point for the downstream stages: it covers them, but not the stages upstream of it.
    *
    * The stages that run synchronously (e.g. `map`, `flatMap`) are called from within the span, so their duration is counted, spans
    * created there are children of it, and if one of them fails, so does the span. If a downstream stage is asynchronous (e.g. `buffer`),
    * the span ends once the element is handed over to it. Each span is opened as a child of the span current when the element is emitted.
    *
    * The parameters are the same as for [[OxTracing.byNameSpan]].
   *
   * not ok - not really each downstream, it spans till first async boundary
    */
  def spanEachDownstream[T](flow: Flow[T])(
      spanName: String,
      spanKind: SpanKind = SpanKind.INTERNAL,
      attributes: Attributes = Attributes.empty(),
      links: Seq[SpanContext] = Seq.empty,
      beforeClose: SpanWithError => Span = _._1
  ): Flow[T] = Flow.usingEmitInline: emit =>
    flow.runToEmit(
      FlowEmit.fromInline: t =>
        self.byNameSpan(emit(t))(spanName, spanKind, attributes, links, beforeClose)
    )

end OxFlowTracing
