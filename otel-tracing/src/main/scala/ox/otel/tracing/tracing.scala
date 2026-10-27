package ox.otel.tracing

import io.opentelemetry.api.common.{AttributeKey, Attributes}
import io.opentelemetry.api.trace.{Span, SpanContext, SpanKind, StatusCode, Tracer}
import io.opentelemetry.context.Context
import ox.{ErrorMode, NoErrorMode}

import java.nio.channels.ClosedByInterruptException
import java.util.concurrent.ExecutionException
import scala.util.boundary
import scala.util.control.ControlThrowable

export ox.flow.tracing.spanEach

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
def span[T](tracer: Tracer)(
    spanName: String,
    spanKind: SpanKind = SpanKind.INTERNAL,
    attributes: Attributes = Attributes.empty(),
    links: Seq[SpanContext] = Seq.empty,
    beforeClose: SpanWithError[Nothing] => Span = _.span
)(t: => T): T =
  spanError[Nothing, [X] =>> X, T](tracer, NoErrorMode)(spanName, spanKind, attributes, links, beforeClose)(t)

/** As [[span]], but `t` can also report an application error, in the sense of the given [[ox.ErrorMode]], e.g. a `Left`. Such an error is
  * not thrown, so it's returned as is, after being recorded on the span: the span's status is set to `ERROR`, with the error's string
  * representation as the description, and an `exception` event is added, with the error's type and message, or the exception itself, if the
  * error is a [[Throwable]].
  *
  * @param beforeClose
  *   callback executed right before closing the span, which receives the span, and the exception thrown by `t` or the application error it
  *   returned, if any. If it throws, the exception is added as suppressed to the one thrown by `t`, or to the application error, as defined
  *   by the error mode, or thrown if `t` succeeded.
  */
def spanError[E, F[_], T](tracer: Tracer, em: ErrorMode[E, F])(
    spanName: String,
    spanKind: SpanKind = SpanKind.INTERNAL,
    attributes: Attributes = Attributes.empty(),
    links: Seq[SpanContext] = Seq.empty,
    beforeClose: SpanWithError[E] => Span = (s: SpanWithError[E]) => s.span
)(t: => F[T]): F[T] =
  val finalizer = spanUnsafe(tracer)(spanName, spanKind, attributes, links)
  var thrown: Throwable | Null = null
  var result: F[T] | Null = null
  var actionableThrown: Option[Throwable] = None
  var applicationError: Option[E] = None
  try
    try result = t
    catch
      case e: Throwable =>
        thrown = e
        if shouldRecordDefect(e) then
          actionableThrown = Some(e)
          finalizer.span.recordException(e)
          finalizer.span.setStatus(StatusCode.ERROR, e.getMessage): Unit
    end try

    if thrown eq null then
      val r = result.nn
      if em.isError(r) then
        val e = em.getError(r)
        applicationError = Some(e)
        recordApplicationError(finalizer.span, e)

    try beforeClose(SpanWithError(finalizer.span, actionableThrown, applicationError)): Unit
    catch
      case e: Throwable =>
        if thrown ne null then thrown.nn.addSuppressed(e)
        else if applicationError.isDefined then result = em.addSuppressedException(result.nn, e)
        else throw e
    end try
  finally finalizer.close()
  end try

  if thrown ne null then throw thrown.nn else result.nn
end spanError

private def recordApplicationError(span: Span, e: Any): Unit =
  e match
    case t: Throwable =>
      span.recordException(t)
      span.setStatus(StatusCode.ERROR, t.getMessage): Unit
    case other =>
      val message = s"$other"
      val tpe = Option(other).fold("null")(_.getClass.getName)
      span.addEvent(
        "exception",
        Attributes.of(AttributeKey.stringKey("exception.type"), tpe, AttributeKey.stringKey("exception.message"), message)
      ): Unit
      span.setStatus(StatusCode.ERROR, message): Unit

private def shouldRecordDefect(e: Throwable): Boolean = e match
  case _: InterruptedException | _: ClosedByInterruptException | _: ControlThrowable | _: boundary.Break[?] => false
  case e: ExecutionException if e.getCause ne null => shouldRecordDefect(e.getCause)
  case _                                           => true
