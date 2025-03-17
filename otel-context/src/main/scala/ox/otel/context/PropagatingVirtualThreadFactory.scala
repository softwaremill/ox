package ox.otel.context

import java.util.concurrent.ThreadFactory
import io.opentelemetry.context.Context

/** A virtual thread factory which propagates the OpenTelemetry [[Context]] when creating new threads.
  *
  * Should be used in [[ox.OxApp]] settings (as `threadFactory`), or using the [[ox.oxThreadFactory]].
  */
class PropagatingVirtualThreadFactory extends ThreadFactory:
  private val delegate = Thread.ofVirtual().factory()

  override def newThread(r: Runnable): Thread =
    val parentContext = Context.current()
    delegate.newThread(() =>
      val scope = parentContext.makeCurrent()
      try r.run()
      finally scope.close()
    )
end PropagatingVirtualThreadFactory
