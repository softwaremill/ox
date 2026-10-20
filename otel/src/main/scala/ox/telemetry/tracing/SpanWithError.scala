package ox.telemetry.tracing

import io.opentelemetry.api.trace.Span

opaque type SpanWithError <: (Span, Option[Throwable]) = (Span, Option[Throwable])
object SpanWithError:
  def apply(span: Span, errorOpt: Option[Throwable]): SpanWithError = (span, errorOpt)
  def apply(span: Span, errorOpt: Throwable): SpanWithError = (span, Option(errorOpt))
  extension (s: SpanWithError)
    def span = s._1
    def errorOpt = s._2
