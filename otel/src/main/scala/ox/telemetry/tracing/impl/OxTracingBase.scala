package ox.telemetry.tracing.impl

import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.*
import ox.telemetry.tracing.flow.OxFlowTracing.OxFlowTracingImpl
import ox.telemetry.tracing.{AutoCloseableSpan}
import ox.telemetry.{AdaptiveOxTracing, ContextHolder}

trait OxTracingBase:
  def currentSpanUnsafe(): Span

  def spanUnsafe(spanName: String,
                 spanKind: SpanKind = SpanKind.INTERNAL,
                 attributes: Attributes = Attributes.empty(),
                 links: Seq[SpanContext] = Seq.empty,
                ): AutoCloseableSpan


object OxTracingBase:
  private[tracing] final class OxTracingBaseImpl(tracer: Tracer, contextHolder: ContextHolder) extends OxTracingBase:
    override def currentSpanUnsafe(): Span = Span.fromContext(contextHolder.get())

    def spanUnsafe(spanName: String,
                   spanKind: SpanKind = SpanKind.INTERNAL,
                   attributes: Attributes = Attributes.empty(),
                   links: Seq[SpanContext] = Seq.empty,
                  ): AutoCloseableSpan = {
      val previousContext = contextHolder.get()
      val builder = tracer.spanBuilder(spanName).setSpanKind(spanKind).setAllAttributes(attributes).setParent(previousContext)
      val withLinks = links.foldLeft(builder)((b, l) => b.addLink(l))
      val started = withLinks.startSpan()
      contextHolder.set(previousContext.`with`(started))

      AutoCloseableSpan.ContextHolderBased(started, contextHolder, previousContext)
    }
