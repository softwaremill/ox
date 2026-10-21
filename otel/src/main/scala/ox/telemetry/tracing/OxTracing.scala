package ox.telemetry.tracing

import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.{Span, SpanContext, SpanKind, StatusCode, Tracer}
import io.opentelemetry.context.Context
import ox.flow.tracing.OxFlowTracing

import java.nio.channels.ClosedByInterruptException
import java.util.concurrent.ExecutionException
import scala.util.boundary
import scala.util.control.ControlThrowable

sealed trait OxTracing extends OxFlowTracing:

  protected def tracer: Tracer

  /** Returns the current span. This is not unsafe per se, but the caller is responsible for using the span properly, e.g. not ending a span
    * that is managed elsewhere.
    */
  def currentSpanUnsafe(): Span = Span.fromContext(Context.current())

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
    val previousContext = Context.current()
    val builder = tracer.spanBuilder(spanName).setSpanKind(spanKind).setAllAttributes(attributes).setParent(previousContext)
    val withLinks = links.foldLeft(builder)((b, l) => b.addLink(l))
    val started = withLinks.startSpan()
    val scope = previousContext.`with`(started).makeCurrent()

    AutoCloseableSpan.Impl(started, scope)
  end spanUnsafe

  /** Evaluates `t` within a new [[Span]]. The span is opened before `t` is evaluated, as a child of the current span, and becomes the
    * current one. It is ended once `t` completes, successfully or not, and the previously current span is restored.
    *
    * If `t` throws, the exception is recorded on the span and the span's status is set to [[io.opentelemetry.api.trace.StatusCode.ERROR]],
    * before `beforeClose` is called. The exception is then re-thrown. This doesn't apply to [[InterruptedException]] and
    * [[java.nio.channels.ClosedByInterruptException]] (an interruption during I/O), [[scala.util.control.ControlThrowable]]s, as well as
    * `boundary.break`: they are not failures, but a way of exiting early, so the span is ended without an error status, and `beforeClose`
    * doesn't receive them, as if `t` succeeded. The same applies to a [[java.util.concurrent.ExecutionException]] wrapping one of them, as
    * when a cancelled fork is joined. Fatal errors, e.g. [[OutOfMemoryError]], are failures and are recorded.
    *
    * To span a [[ox.flow.Flow]], use [[OxFlowTracing.spanStages]].
    *
    * @param beforeClose
    *   Run right before the span is closed, e.g. to set attributes or add events. The second element of the argument is the failure of `t`,
    *   if any, that is the exception it threw, other than the ones which are not failures. If `beforeClose` itself throws, the exception is
    *   added as suppressed to the one thrown by `t`, including the ones which are not failures, or thrown if `t` succeeded. The span is
    *   closed in either case.
    */
  private[ox] def byNameSpan[T](t: => T)(
      spanName: String,
      spanKind: SpanKind = SpanKind.INTERNAL,
      attributes: Attributes = Attributes.empty(),
      links: Seq[SpanContext] = Seq.empty,
      beforeClose: SpanWithError => Span = _.span
  ): T =
    var error: Throwable | Null = null
    var actionableError: Throwable | Null = null
    var finalizer: AutoCloseableSpan | Null = null
    try
      val finalize = spanUnsafe(spanName, spanKind, attributes, links)
      finalizer = finalize
      t
    catch
      case e: Throwable =>
        error = e
        if shouldRecord(e) then
          actionableError = e
          if finalizer ne null then
            finalizer.nn.span.recordException(e)
            finalizer.nn.span.setStatus(StatusCode.ERROR, e.getMessage): Unit
        throw e
    finally
      try if finalizer ne null then beforeClose(SpanWithError(finalizer.nn.span, actionableError)): Unit
      catch
        case e: Throwable =>
          if error ne null then error.nn.addSuppressed(e)
          else throw e
      finally if finalizer ne null then finalizer.nn.close()
      end try
    end try

  end byNameSpan

  private def shouldRecord(e: Throwable): Boolean = e match
    case _: InterruptedException | _: ClosedByInterruptException | _: ControlThrowable | _: boundary.Break[?] => false
    case e: ExecutionException if e.getCause ne null                                                          => shouldRecord(e.getCause)
    case _                                                                                                    => true
end OxTracing

object OxTracing:

  /** Creates tracing, which keeps spans in the OpenTelemetry [[io.opentelemetry.context.Context]]. For the context to be propagated to
    * forks, the [[ox.otel.context.PropagatingVirtualThreadFactory]] is required: it has to be set as the Ox thread factory, e.g. using
    * [[ox.OxApp.Settings.threadFactory]] or [[ox.setOxThreadFactory]].
    */
  def apply(tracer: Tracer): OxTracing = Impl(tracer)

  private final class Impl(val tracer: Tracer) extends OxTracing

end OxTracing
