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
end OxTracing
