package ox.telemetry.tracing

import io.opentelemetry.api.trace.Span
import io.opentelemetry.context.Scope

/** [[AutoCloseable]] wrapper over a [[Span]]
  *
  * calling [[close]] will end wrapped [[Span]] and [[Scope]] created when opening a [[Span]], restoring previous current [[Span]]
  */
sealed trait AutoCloseableSpan extends AutoCloseable:
  def span: Span

private object AutoCloseableSpan:
  private[ox] final class Impl(val span: Span, openedScope: Scope) extends AutoCloseableSpan:
    def close(): Unit =
      span.end()
      openedScope.close()
