package ox.telemetry.tracing

import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.{Span, SpanKind, Tracer}
import io.opentelemetry.context.{Context, ContextStorage, Scope}
import ox.otel.context.PropagatingVirtualThreadFactory
import ox.telemetry.ContextHolder
import ox.telemetry.tracing.flow.OxFlowTracing
import ox.telemetry.tracing.internal.OxTracingBase

sealed trait OxTracing extends OxTracingBase, OxFlowTracing

object OxTracing:

  /** Tracing that relies on [[PropagatingVirtualThreadFactory]] and checks if its properly set
   * */
  def native(tracer: Tracer): OxTracing =
    val base = OxTracingBase(tracer, ContextHolder.CheckingThreadFactory)
    Impl(base)

  /** Custom tracing that leaves propagation of [[Context]] to be implemented by user, for out of the box integration use [[native]]
   * */
  def custom(tracer: Tracer): OxTracing =
    val base = OxTracingBase(tracer, ContextHolder.Delegating)
    Impl(base)

  private class Impl(base: OxTracingBase) extends OxTracing:
    export base.*
end OxTracing

