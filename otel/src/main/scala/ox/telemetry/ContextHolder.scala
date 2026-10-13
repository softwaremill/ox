package ox.telemetry

import io.opentelemetry.context.{Context, Scope}
import ox.otel.context.PropagatingVirtualThreadFactory

/** Abstraction managing an OpenTelemetry [[Context]]
  */
sealed trait ContextHolder:
  def get(): Context

  def set(context: Context): Scope

private[telemetry] object ContextHolder:

  private[telemetry] object Delegating extends ContextHolder:

    override def get(): Context = Context.current()

    override def set(context: Context): Scope = context.makeCurrent()

  private[telemetry] object CheckingThreadFactory extends ContextHolder:
    private var checked = false
    private val wrapped: ContextHolder = Delegating

    override def get(): Context =
      checkThreadFactory()
      wrapped.get()

    override def set(context: Context): Scope =
      checkThreadFactory()
      wrapped.set(context)

    private def checkThreadFactory() =
      if !checked then
        if !ox.oxThreadFactory.isInstanceOf[PropagatingVirtualThreadFactory] then
          System.err.println(
            s"Default OpenTelemetry integration should use ${classOf[PropagatingVirtualThreadFactory].getName}, if you want to use OpenTelemetry with your own oxThreadFactory, you need to take care of propagation of OpenTelemetry context to newly created VirtualThreads"
          )
        checked = true
  end CheckingThreadFactory
end ContextHolder
