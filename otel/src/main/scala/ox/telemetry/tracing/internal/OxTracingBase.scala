package ox.telemetry.tracing.internal

import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.*
import ox.telemetry.ContextHolder
import ox.telemetry.tracing.AutoCloseableSpan

private[tracing] trait OxTracingBase:
  def currentSpanUnsafe(): Span

  def spanUnsafe(
      spanName: String,
      spanKind: SpanKind = SpanKind.INTERNAL,
      attributes: Attributes = Attributes.empty(),
      links: Seq[SpanContext] = Seq.empty
  ): AutoCloseableSpan

  extension [T](t: => T)
    def byNameSpan(
        spanName: String,
        spanKind: SpanKind,
        attributes: Attributes,
        links: Seq[SpanContext],
        beforeClose: ((Span, Option[Throwable])) => Span
    ): T
  end extension
end OxTracingBase

private[tracing] object OxTracingBase:
  def apply(tracer: Tracer, contextHolder: ContextHolder): OxTracingBase = new OxTracingBaseImpl(tracer, contextHolder)

  private final class OxTracingBaseImpl(tracer: Tracer, contextHolder: ContextHolder) extends OxTracingBase:

    override def currentSpanUnsafe(): Span = Span.fromContext(contextHolder.get())

    def spanUnsafe(
        spanName: String,
        spanKind: SpanKind = SpanKind.INTERNAL,
        attributes: Attributes = Attributes.empty(),
        links: Seq[SpanContext] = Seq.empty
    ): AutoCloseableSpan =
      val previousContext = contextHolder.get()
      val builder = tracer.spanBuilder(spanName).setSpanKind(spanKind).setAllAttributes(attributes).setParent(previousContext)
      val withLinks = links.foldLeft(builder)((b, l) => b.addLink(l))
      val started = withLinks.startSpan()
      contextHolder.set(previousContext.`with`(started))

      AutoCloseableSpan.ContextHolderBased(started, contextHolder, previousContext)
    end spanUnsafe

    extension [T](t: => T)
      override def byNameSpan(
          spanName: String,
          spanKind: SpanKind,
          attributes: Attributes,
          links: Seq[SpanContext],
          beforeClose: ((Span, Option[Throwable])) => Span
      ): T =
        var error: Throwable | Null = null
        var finalizer: AutoCloseableSpan | Null = null
        try
          val finalize = spanUnsafe(spanName, spanKind, attributes, links)
          finalizer = finalize
          t
        catch
          case e: Throwable =>
            error = e
            throw e
        finally
          try if finalizer ne null then beforeClose(finalizer.nn.span, Option(error)): Unit
          catch
            case e: Throwable =>
              if error ne null then error.nn.addSuppressed(e)
              else throw e
          finally if finalizer ne null then finalizer.nn.close()
          end try
        end try
    end extension

  end OxTracingBaseImpl
end OxTracingBase
