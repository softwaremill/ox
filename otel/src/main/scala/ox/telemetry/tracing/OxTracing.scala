package ox.telemetry.tracing

import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.{Span, SpanKind, Tracer}
import ox.telemetry.ContextHolder
import ox.telemetry.tracing.flow.OxFlowTracing
import ox.telemetry.tracing.internal.OxTracingBase

sealed trait OxTracing extends OxTracingBase, OxFlowTracing

object OxTracing:
  def native(tracer: Tracer): OxTracing =
    val ch = ContextHolder.Native
    val base = OxTracingBase(tracer, ch)
    Impl(base)

  private class Impl(base: OxTracingBase) extends OxTracing:
    export base.*
end OxTracing
