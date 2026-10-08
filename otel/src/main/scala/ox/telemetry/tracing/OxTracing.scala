package ox.telemetry.tracing

import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.{Span, SpanContext, SpanKind, Tracer}
import ox.telemetry.ContextHolder
import ox.telemetry.tracing.flow.OxFlowTracing.OxFlowTracingImpl
import ox.telemetry.tracing.flow.{OxFlowTracing, OxForkTracing}
import ox.telemetry.tracing.impl.OxTracingBase
import ox.telemetry.tracing.impl.OxTracingBase.OxTracingBaseImpl

trait OxTracing extends OxFlowTracing, OxTracingByName, OxTracingBase, OxForkTracing

object OxTracing:
  extension [T: TracingStrategy](t: T)
    def span(spanName: String,
             spanKind: SpanKind = SpanKind.INTERNAL,
             attributes: Attributes = Attributes.empty(),
             links: Seq[SpanContext] = Seq.empty,
             beforeClose: ((Span, Option[Throwable])) => Span = _._1) =
      summon[TracingStrategy[T]].span(t)(spanName, spanKind, attributes, links, beforeClose)


import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.{Span, SpanContext, SpanKind, Tracer}
import ox.flow.Flow
import ox.telemetry.tracing.flow.OxFlowTracing.OxFlowTracingImpl
import ox.telemetry.tracing.flow.{OxFlowTracing, OxForkTracing}
import ox.telemetry.tracing.impl.OxTracingBase
import ox.telemetry.tracing.impl.OxTracingBase.OxTracingBaseImpl
import ox.telemetry.{ContextHolder, tracing}

import scala.compiletime.summonInline


trait TracingStrategy[T]:
  def span(t: => T)(spanName: String, spanKind: SpanKind, attributes: Attributes, links: Seq[SpanContext], beforeClose: ((Span, Option[Throwable])) => Span): T

object TracingStrategy extends LowPrio:
  given [T]: TracingStrategy[Flow[T]] with {
    override def span(flow: => Flow[T])(spanName: String, spanKind: SpanKind, attributes: Attributes, links: Seq[SpanContext], beforeClose: ((Span, Option[Throwable])) => Span): Flow[T] =
      val preEvaluate = flow
      Flow.usingEmit: emit =>
        byName.span(preEvaluate.runToEmit(
          emit
        ))(spanName, spanKind, attributes, links, beforeClose)

  }

trait LowPrio:
  inline def isAbstract[T](using q: quoted.Quotes, t: quoted.Type[T]): Boolean =
    import q.reflect.{*, given}
    import q.{*, given}
    val tRefSym = TypeRepr.of[T].typeSymbol
    tRefSym.isAbstractType


  inline given byName[T](using q: quoted.Quotes, t: quoted.Type[T]): TracingStrategy[T] =
    if isAbstract[T] then
      summonInline[TracingStrategy[T]]
    else
      byName[T]


  def byName[T]: TracingStrategy[T] = new {
    extension (t: => T)
      override def span(spanName: String, spanKind: SpanKind, attributes: Attributes, links: Seq[SpanContext], beforeClose: ((Span, Option[Throwable])) => Span): T =
        var error: Throwable | Null = null
        var finalizer: AutoCloseableSpan | Null = null
        try
          val finalize = ??? //spanUnsafe(spanName, spanKind, attributes, links)
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









//  def native(tracer: Tracer): OxTracing =
//    val ch = ContextHolder.Native
//    val base = OxTracingBaseImpl(tracer, ch)
//    val byName = OxTracingByName.Impl(base)
//    val oxFlowTracing = OxFlowTracingImpl(byName)
//    val oxForkTracing: OxForkTracing = null
//    Impl(oxFlowTracing, byName, base,oxForkTracing)
//
//  def nativeAdaptive(tracer: Tracer): AdaptiveOxTracing =
//    val ch = ContextHolder.Native
//    val base = OxTracingBaseImpl(tracer, ch)
//    val byName = OxTracingByName.Impl(base)
//    val oxFlowTracing = OxFlowTracingImpl(byName)
//    val oxForkTracing: OxForkTracing = null
//    AdaptiveOxTracing(base, byName, oxFlowTracing,oxForkTracing)
//
//  private class Impl(flow: OxFlowTracing, byName: OxTracingByName, base: OxTracingBase, fork:OxForkTracing) extends OxTracing:
//    export flow.*
//    export byName.*
//    export base.*
//    export fork.*
//
//
