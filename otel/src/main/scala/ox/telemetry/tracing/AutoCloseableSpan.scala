package ox.telemetry.tracing

import io.opentelemetry.api.trace.Span
import io.opentelemetry.context.Context
import ox.telemetry.ContextHolder

trait AutoCloseableSpan extends AutoCloseable:
  def span: Span

object AutoCloseableSpan:
  private [tracing] final class ContextHolderBased(val span: Span, contextHolder: ContextHolder, previousContext: Context) extends AutoCloseableSpan:
    def close(): Unit =
      span.end()
      contextHolder.set(previousContext)