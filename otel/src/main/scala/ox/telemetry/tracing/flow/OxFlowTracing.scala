package ox.telemetry.tracing.flow

import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.{Span, SpanContext, SpanKind}
import ox.flow.Flow
import ox.telemetry.tracing.OxTracingByName

trait OxFlowTracing:
  def span[T](flow: Flow[T])(spanName: String,
                             spanKind: SpanKind = SpanKind.INTERNAL,
                             attributes: Attributes = Attributes.empty(),
                             links: Seq[SpanContext] = Seq.empty,
                             beforeClose: ((Span, Option[Throwable])) => Span = _._1
  ): Flow[T]

object OxFlowTracing:
  class OxFlowTracingImpl(oxTracingByName: OxTracingByName) extends OxFlowTracing:
    override def span[T](flow: Flow[T])(spanName: String, spanKind: SpanKind, attributes: Attributes, links: Seq[SpanContext], beforeClose: ((Span, Option[Throwable])) => Span): Flow[T] =
      Flow.usingEmit: emit =>
        oxTracingByName.byNameSpan(flow.runToEmit(
          emit
        ))(spanName, spanKind, attributes, links, beforeClose)



//    extension [T](flow: Flow[T])
//      def span(spanName: String, spanKind: SpanKind, attributes: Attributes, links: Seq[SpanContext], beforeClose: ((Span, Option[Throwable])) => Span): Flow[T] =
//       
