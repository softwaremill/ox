package ox.telemetry

import io.opentelemetry.context.{Context, Scope}
import ox.otel.context.PropagatingVirtualThreadFactory

/** Abstraction over how [[OxTracing]] interacts with OpenTelemetry [[Context]].
  */
sealed trait ContextHolder:
  /** Returns the current [[Context]]. */
  def get(): Context

  /** Makes the given `context` current. Closing the returned [[Scope]] restores the previously current context. */
  def set(context: Context): Scope

private[telemetry] object ContextHolder:

  /** Delegates to OpenTelemetry Context. Propagating the context to new (virtual) threads is
    * left to the user.
    */
  private[telemetry] object Delegating extends ContextHolder:

    override def get(): Context = Context.current()

    override def set(context: Context): Scope = context.makeCurrent()

  /** As [[Delegating]], but warns when [[ox.oxThreadFactory]] is not a [[PropagatingVirtualThreadFactory]],
    * as in that case the context won't be propagated to forks.
    */
  private[telemetry] object CheckingThreadFactory extends ContextHolder:
    @volatile private var checked = false
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
