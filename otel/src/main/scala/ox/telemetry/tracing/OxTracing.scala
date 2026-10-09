package ox.telemetry.tracing

import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.{Span, SpanKind, Tracer}
import ox.flow.Flow
import ox.telemetry.ContextHolder
import ox.telemetry.tracing.flow.OxFlowTracing
import ox.telemetry.tracing.flow.OxFlowTracing.OxFlowTracingImpl
import ox.telemetry.tracing.internal.OxTracingBase

sealed trait OxTracing extends OxTracingBase, OxFlowTracing

object OxTracing:
  def native(tracer: Tracer): OxTracing =
    val ch = ContextHolder.Native
    val base = OxTracingBase(tracer, ch)
    val oxFlowTracing = OxFlowTracingImpl(base)
    Impl(base, oxFlowTracing)

  private class Impl(base: OxTracingBase, flow: OxFlowTracing) extends OxTracing:
    export flow.*
    export base.*





//
//  override def currentSpanUnsafe(): Span = Span.fromContext(contextHolder.get())
//
//
//  def spanUnsafe(spanName: String,
//                 spanKind: SpanKind = SpanKind.INTERNAL,
//                 attributes: Attributes = Attributes.empty(),
//                 links: Seq[SpanContext] = Seq.empty,
//                ): AutoCloseableSpan = {
//    val previousContext = contextHolder.get()
//    val builder = tracer.spanBuilder(spanName).setSpanKind(spanKind).setAllAttributes(attributes).setParent(previousContext)
//    val withLinks = links.foldLeft(builder)((b, l) => b.addLink(l))
//    val started = withLinks.startSpan()
//    contextHolder.set(previousContext.`with`(started))
//
//    AutoCloseableSpan.ContextHolderBased(started, contextHolder, previousContext)
//  }
//
//
//  def byNameSpan[T](t: T)(spanName: String,
//                          spanKind: SpanKind,
//                          attributes: Attributes,
//                          links: Seq[SpanContext],
//                          beforeClose: ((Span, Option[Throwable])) => Span
//  ) = {
//    var error: Throwable | Null = null
//    var finalizer: AutoCloseableSpan | Null = null
//    try
//      val finalize = spanUnsafe(spanName, spanKind, attributes, links)
//      finalizer = finalize
//      t
//    catch
//      case e: Throwable =>
//        error = e
//        throw e
//    finally
//      try
//        if finalizer ne null then beforeClose(finalizer.nn.span, Option(error)): Unit
//      catch
//        case e: Throwable =>
//          if error ne null then error.nn.addSuppressed(e)
//          else throw e
//      finally
//        if finalizer ne null then finalizer.nn.close()
//      end try
//    end try
//  }
//
//  def spanFlow[T](flow: Flow[T])(spanName: String, spanKind: SpanKind, attributes: Attributes, links: Seq[SpanContext], beforeClose: ((Span, Option[Throwable])) => Span): Flow[T] =
//    Flow.usingEmit: emit =>
//      byNameSpan(flow.runToEmit(
//        emit
//      ))(spanName, spanKind, attributes, links, beforeClose)
//}
//
//
////trait TracingStrategy[T]:
////  def span(t: => T)(spanName: String, spanKind: SpanKind, attributes: Attributes, links: Seq[SpanContext], beforeClose: ((Span, Option[Throwable])) => Span): T
////
////object TracingStrategy extends LowPrio:
////  given [T]: TracingStrategy[Flow[T]] with {
////    override def span(flow: => Flow[T])(spanName: String, spanKind: SpanKind, attributes: Attributes, links: Seq[SpanContext], beforeClose: ((Span, Option[Throwable])) => Span): Flow[T] =
////      val preEvaluate = flow
////      Flow.usingEmit: emit =>
////        byName.span(preEvaluate.runToEmit(
////          emit
////        ))(spanName, spanKind, attributes, links, beforeClose)
////
////  }
////
////trait LowPrio:
////  inline def isAbstract[T](using q: quoted.Quotes, t: quoted.Type[T]): Boolean =
////    import q.reflect.{*, given}
////    import q.{*, given}
////    val tRefSym = TypeRepr.of[T].typeSymbol
////    tRefSym.isAbstractType
////
////
////  inline given byName[T](using q: quoted.Quotes, t: quoted.Type[T]): TracingStrategy[T] =
////    if isAbstract[T] then
////      summonInline[TracingStrategy[T]]
////    else
////      byName[T]
////
////
////  def byName[T]: TracingStrategy[T] = new {
////    extension (t: => T)
////      override def span(spanName: String, spanKind: SpanKind, attributes: Attributes, links: Seq[SpanContext], beforeClose: ((Span, Option[Throwable])) => Span): T =
////        var error: Throwable | Null = null
////        var finalizer: AutoCloseableSpan | Null = null
////        try
////          val finalize = ??? //spanUnsafe(spanName, spanKind, attributes, links)
////          finalizer = finalize
////          t
////        catch
////          case e: Throwable =>
////            error = e
////            throw e
////        finally
////          try
////            if finalizer ne null then beforeClose(finalizer.nn.span, Option(error)): Unit
////          catch
////            case e: Throwable =>
////              if error ne null then error.nn.addSuppressed(e)
////              else throw e
////          finally
////            if finalizer ne null then finalizer.nn.close()
////          end try
////        end try
////  }
//
