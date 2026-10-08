package ox.telemetry.tracing

import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.*
import ox.telemetry.tracing.flow.OxFlowTracing.OxFlowTracingImpl
import ox.telemetry.tracing.impl.OxTracingBase
import ox.telemetry.{AdaptiveOxTracing, ContextHolder}


trait OxTracingByName:
  extension [T](t: => T)
    def byNameSpan(spanName: String,
                   spanKind: SpanKind = SpanKind.INTERNAL,
                   attributes: Attributes = Attributes.empty(),
                   links: Seq[SpanContext] = Seq.empty,
                   beforeClose: ((Span, Option[Throwable])) => Span = _._1): T

object OxTracingByName:
  class Impl(oxTracingBase: OxTracingBase) extends OxTracingByName:
    import oxTracingBase.*

    extension [T](t: => T)
      def byNameSpan(spanName: String,
                     spanKind: SpanKind,
                     attributes: Attributes,
                     links: Seq[SpanContext],
                     beforeClose: ((Span, Option[Throwable])) => Span
                    ) = {
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
          try
            if finalizer ne null then beforeClose(finalizer.nn.span, Option(error)): Unit
          catch
            case e: Throwable =>
              if error ne null then error.nn.addSuppressed(e)
              else throw e
          finally
            if finalizer ne null then finalizer.nn.close()
          end try
        end try
      }
  