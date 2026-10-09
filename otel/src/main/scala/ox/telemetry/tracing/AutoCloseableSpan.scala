package ox.telemetry.tracing

import io.opentelemetry.api.trace.Span
import io.opentelemetry.context.Context
import ox.telemetry.ContextHolder

sealed trait AutoCloseableSpan extends AutoCloseable:
  def span: Span

private object AutoCloseableSpan:
  private [ox] final class ContextHolderBased(val span: Span, contextHolder: ContextHolder, previousContext: Context) extends AutoCloseableSpan:
    def close(): Unit =
      span.end()
      contextHolder.set(previousContext)
