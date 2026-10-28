package ox.otel.tracing

import io.opentelemetry.api.trace.Span

/** A span, together with the exception thrown by the code it covers, if any, and the application error it returned, if any, in the sense of
  * an [[ox.ErrorMode]]. Without an error mode, all the failures are exceptions.
  */
final case class SpanWithError[+E](span: Span, defectOpt: Option[Throwable], errorOpt: Option[E])
