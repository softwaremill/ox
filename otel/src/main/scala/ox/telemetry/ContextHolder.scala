package ox.telemetry

import io.opentelemetry.context.Context
import ox.ForkLocal
import ox.internal.currentLocals
import ox.otel.context.PropagatingVirtualThreadFactory

trait ContextHolder:
  def get(): Context

  def set(context: Context): Unit

object ContextHolder:
  object Native extends ContextHolder:
    private var checked = false

    override def get(): Context =
      checkThreadFactory()
      Context.current()

    override def set(context: Context): Unit =
      checkThreadFactory()
      context.makeCurrent(): Unit

    private def checkThreadFactory() =
      if !checked then
        if !ox.oxThreadFactory.isInstanceOf[PropagatingVirtualThreadFactory] then
          System.err.println(s"Otel should use ${classOf[PropagatingVirtualThreadFactory].getName}")
        checked = true
  end Native

  val contextForkLocal = ForkLocal(Context.current())

  object ForkLocalBased extends ContextHolder:

    override def get(): Context = contextForkLocal.get()

    override def set(context: Context): Unit =
      currentLocals.set(contextForkLocal, context): Unit

  end ForkLocalBased
end ContextHolder
