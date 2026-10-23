package ox.telemetry.tracing

import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.{Span, SpanContext, SpanKind, StatusCode, Tracer}
import io.opentelemetry.context.Context

import java.nio.channels.ClosedByInterruptException
import java.util.concurrent.ExecutionException
import scala.util.boundary
import scala.util.control.ControlThrowable

export ox.flow.tracing.{mapSpanned, spanEachDownstream, spanStages}

/** Returns the current span. This is not unsafe per se, but the caller is responsible for using the span properly, e.g. not ending a span
  * that is managed elsewhere.
  */
def currentSpanUnsafe(): Span = Span.fromContext(Context.current())

/** Opens a new [[Span]] as a child of the current span. The new span becomes the current one.
  *
  * Unsafe, because the caller is responsible for closing the returned [[AutoCloseableSpan]], which ends the span and restores the
  * previously current one. Scoped usage, e.g. [[ox.useCloseableInScope]], guarantees that.
  *
  * For the context to be propagated to forks, the [[ox.otel.context.PropagatingVirtualThreadFactory]] is required: it has to be set as the
  * Ox thread factory, e.g. using [[ox.OxApp.Settings.threadFactory]] or [[ox.setOxThreadFactory]].
  *
  * @return
  *   An [[AutoCloseableSpan]], which closes the opened span.
  */
def spanUnsafe(tracer: Tracer)(
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

/** Evaluates `t` within a new span, which is made a child of the current span, and becomes the current one. The span is ended once `t`
  * completes, and the previously current span is restored.
  *
  * If `t` throws, the exception is recorded on the span as an event, and the span's status is set to `ERROR`. Interruptions,
  * [[scala.util.control.ControlThrowable]]s and `boundary.break` are not failures: they end the span normally.
  *
  * @param beforeClose
  *   callback executed right before closing the span, which receives the span and the failure of `t`, if any. If it throws, the exception
  *   is added as suppressed to the one thrown by `t`, or thrown if `t` succeeded.
  */
private[ox] def byNameSpan[T](tracer: Tracer)(t: => T)(
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
    val finalize = spanUnsafe(tracer)(spanName, spanKind, attributes, links)
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
