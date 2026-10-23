package ox.telemetry.tracing

import io.opentelemetry.api.common.{AttributeKey, Attributes}
import io.opentelemetry.api.trace.{Span, SpanContext, SpanKind, StatusCode, Tracer}
import io.opentelemetry.context.Context
import ox.{ErrorMode, NoErrorMode}

import java.nio.channels.ClosedByInterruptException
import java.util.concurrent.ExecutionException
import scala.util.{Try, boundary}
import scala.util.control.ControlThrowable

export ox.flow.tracing.{spanEach, spanStages}

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
extension [T](t: => T)
  def span(tracer: Tracer)(
      spanName: String,
      spanKind: SpanKind = SpanKind.INTERNAL,
      attributes: Attributes = Attributes.empty(),
      links: Seq[SpanContext] = Seq.empty,
      beforeClose: SpanWithError[Nothing] => Span = _.span
  ): T =
    spanError[Nothing, [X] =>> X, T](t)(tracer, NoErrorMode)(spanName, spanKind, attributes, links, beforeClose)
end extension

/** As [[span]], but `t` can also report an application error, in the sense of the given [[ox.ErrorMode]], e.g. a `Left`. Such an error is
  * not thrown, so it's returned as is, after being recorded on the span: the span's status is set to `ERROR`, with the error's string
  * representation as the description, and an `exception` event is added, with the error's type and message, or the exception itself, if the
  * error is a [[Throwable]].
  *
  * An application error carried by a `boundary.break`, e.g. the one used by `either`'s `.ok()`, is recorded in the same way, and the break
  * is propagated.
  *
  * @param beforeClose
  *   callback executed right before closing the span, which receives the span, and the exception thrown by `t` or the application error it
  *   returned, if any. If it throws, the exception is added as suppressed to the one thrown by `t`, or to the application error, as defined
  *   by the error mode, or thrown if `t` succeeded. An application error carried by a `boundary.break` thrown by `beforeClose` is added to
  *   the application error returned by `t` as a suppressed error; if `t` succeeded, it is recorded on the span, and the break is
  *   propagated.
  */
extension [E, F[_], T](t: => F[T])
  def spanError(tracer: Tracer, em: ErrorMode[E, F])(
      spanName: String,
      spanKind: SpanKind = SpanKind.INTERNAL,
      attributes: Attributes = Attributes.empty(),
      links: Seq[SpanContext] = Seq.empty,
      beforeClose: SpanWithError[E] => Span = (s: SpanWithError[E]) => s.span
  ): F[T] =
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
          breakError(em, e) match
            case Some(error) =>
              applicationError = Some(error)
              recordApplicationError(finalizer.span, error)
            case None =>
              if shouldRecordDefect(e) then
                actionableThrown = Some(e)
                finalizer.span.recordException(e)
                finalizer.span.setStatus(StatusCode.ERROR, e.getMessage): Unit
          end match
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
          else if applicationError.isDefined then
            result = breakError(em, e) match
              case Some(error) => em.addSuppressedError(result.nn, error)
              case None        => em.addSuppressedException(result.nn, e)
          else
            breakError(em, e).foreach(recordApplicationError(finalizer.span, _))
            throw e
      end try
    finally finalizer.close()
    end try

    if thrown ne null then throw thrown.nn else result.nn
  end spanError
end extension

/** The application error carried by a `boundary.break`, e.g. the one used by `either`'s `.ok()`, if the value is an error in the given
  * mode.
  */
private def breakError[E, F[_]](em: ErrorMode[E, F], e: Throwable): Option[E] = e match
  case b: boundary.Break[?] =>
    try
      val casted = b.value.asInstanceOf[F[E]]
      if em.isError(casted) then Some(em.getError(casted)) else None
    catch case _: ClassCastException => None
  case _ => None

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
