package ox.telemetry.tracing

import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.{Span, SpanContext, SpanKind, StatusCode, Tracer}
import ox.telemetry.ContextHolder
import ox.telemetry.tracing.flow.OxFlowTracing

sealed trait OxTracing extends OxFlowTracing:

  protected def contextHolder: ContextHolder

  protected def tracer: Tracer

  /** Returns the current span. This is not unsafe per se, but the caller is responsible for using the span properly, e.g. not ending a span
    * that is managed elsewhere.
    */
  def currentSpanUnsafe(): Span = Span.fromContext(contextHolder.get())

  /** Opens a new [[Span]] as a child of the current span. The new span becomes the current one.
    *
    * Unsafe, because the caller is responsible for closing the returned [[AutoCloseableSpan]], which ends the span and restores the
    * previously current one. Scoped usage, e.g. [[ox.useCloseableInScope]], guarantees that.
    *
    * @return
    *   An [[AutoCloseableSpan]], which closes the opened span.
    */
  def spanUnsafe(
      spanName: String,
      spanKind: SpanKind = SpanKind.INTERNAL,
      attributes: Attributes = Attributes.empty(),
      links: Seq[SpanContext] = Seq.empty
  ): AutoCloseableSpan =
    val previousContext = contextHolder.get()
    val builder = tracer.spanBuilder(spanName).setSpanKind(spanKind).setAllAttributes(attributes).setParent(previousContext)
    val withLinks = links.foldLeft(builder)((b, l) => b.addLink(l))
    val started = withLinks.startSpan()
    val scope = contextHolder.set(previousContext.`with`(started))

    AutoCloseableSpan.Impl(started, scope)
  end spanUnsafe

  /** Evaluates `t` within a new [[Span]]. The span is opened before `t` is evaluated, as a child of the current span, and becomes the
    * current one. It is ended once `t` completes, successfully or not, and the previously current span is restored.
    *
    * If `t` throws, the exception is recorded on the span and the span's status is set to [[io.opentelemetry.api.trace.StatusCode.ERROR]],
    * before `beforeClose` is called. The exception is then re-thrown.
    *
    * To span a [[ox.flow.Flow]], use [[OxFlowTracing.span]].
    *
    * @param beforeClose
    *   Run right before the span is closed, e.g. to set attributes or add events. The second element of the argument is the exception
    *   thrown by `t`, if any. If `beforeClose` itself throws, the exception is added as suppressed to the one thrown by `t`, or thrown if
    *   `t` succeeded. The span is closed in either case.
    */
  def byNameSpan[T](t: => T)(
      spanName: String,
      spanKind: SpanKind = SpanKind.INTERNAL,
      attributes: Attributes = Attributes.empty(),
      links: Seq[SpanContext] = Seq.empty,
      beforeClose: ((Span, Option[Throwable])) => Span = _._1
  ): T =
    var error: Throwable | Null = null
    var finalizer: AutoCloseableSpan | Null = null
    try
      val finalize = spanUnsafe(spanName, spanKind, attributes, links)
      finalizer = finalize
      t
    catch
      case e: Throwable =>
        error = e
        if finalizer ne null then
          finalizer.nn.span.recordException(e)
          finalizer.nn.span.setStatus(StatusCode.ERROR, e.getMessage): Unit
        throw e
    finally
      try if finalizer ne null then beforeClose(finalizer.nn.span, Option(error)): Unit
      catch
        case e: Throwable =>
          if error ne null then error.nn.addSuppressed(e)
          else throw e
      finally if finalizer ne null then finalizer.nn.close()
      end try
    end try

  end byNameSpan
end OxTracing

object OxTracing:

  /** Tracing that relies on the [[ox.otel.context.PropagatingVirtualThreadFactory]] to propagate the context to forks. A warning is printed
    * to stderr on first use, if the thread factory is not set up.
    *
    * @see
    *   [[ox.OxApp.Settings.threadFactory]], [[ox.setOxThreadFactory]]
    */
  def native(tracer: Tracer): OxTracing = Impl(tracer, ContextHolder.CheckingThreadFactory)

  /** Tracing which leaves the propagation of the [[io.opentelemetry.context.Context]] to new threads to the user. For an out-of-the-box
    * integration, use [[native]] together with the [[ox.otel.context.PropagatingVirtualThreadFactory]].
    */
  def custom(tracer: Tracer): OxTracing = Impl(tracer, ContextHolder.Delegating)

  private final class Impl(val tracer: Tracer, val contextHolder: ContextHolder) extends OxTracing

end OxTracing
