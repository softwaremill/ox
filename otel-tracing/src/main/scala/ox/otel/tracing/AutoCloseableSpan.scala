package ox.otel.tracing

import io.opentelemetry.api.trace.Span
import io.opentelemetry.context.Scope

/** An opened [[Span]], which is also the current one, together with the [[Scope]] that made it current.
  *
  * Calling [[close]] ends the span and closes the scope, restoring the previously current span. Prefer scoped usage, e.g.
  * [[ox.useCloseableInScope]], so that the span is always closed.
  */
sealed trait AutoCloseableSpan extends AutoCloseable:
  /** The opened span, e.g. to set attributes or add events. */
  def span: Span

private object AutoCloseableSpan:
  private[ox] final class Impl(val span: Span, openedScope: Scope) extends AutoCloseableSpan:
    def close(): Unit =
      span.end()
      openedScope.close()
