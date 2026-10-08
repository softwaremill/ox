package ox.telemetry

import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.*
import ox.flow.Flow
import ox.telemetry.tracing.OxTracingByName
import ox.telemetry.tracing.flow.{OxFlowTracing, OxForkTracing}
import ox.telemetry.tracing.impl.OxTracingBase

import scala.compiletime.erasedValue

//final class AdaptiveOxTracing(base: OxTracingBase,oxTracingByName: OxTracingByName, oxFlowTracing: OxFlowTracing,oxForkTracing: OxForkTracing):
//  export oxTracingByName.{span as _, *}
//  export oxFlowTracing.{span as _, *}
//  export oxForkTracing.{spanFork as _, *}
//  export base.{span as _, *}
//  extension [T](t: => T)
//    inline def span(spanName: String,
//                    spanKind: SpanKind = SpanKind.INTERNAL,
//                    attributes: Attributes = Attributes.empty(),
//                    links: Seq[SpanContext] = Seq.empty,
//                    beforeClose: ((Span, Option[Throwable])) => Span = _._1
//                   ): T =
//      inline erasedValue[T] match
//        case _: Flow[t] => oxFlowTracing.span(t.asInstanceOf[Flow[t]])(spanName, spanKind, attributes, links, beforeClose).asInstanceOf[T]
//        case _ => oxTracingByName.byNameSpan(t)(spanName, spanKind, attributes, links, beforeClose) match {
//          case itWasFlow: Flow[?] => System.err.println("AdaptiveOxTracing improperly applied to a flow"); itWasFlow
//          case ok => ok
//        }