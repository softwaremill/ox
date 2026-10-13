package ox.telemetry.tracing

import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.{Span, SpanContext, SpanKind, Tracer}
import ox.telemetry.ContextHolder
import ox.telemetry.tracing.flow.OxFlowTracing

sealed trait OxTracing extends OxFlowTracing:

  protected def contextHolder: ContextHolder

  protected def tracer: Tracer

  /** Gets current span, not unsafe per se, but bears user with responsibility of proper usage of obtained span
    */
  def currentSpanUnsafe(): Span = Span.fromContext(contextHolder.get())

  /** Opens a new [[Span]] and make it child of current [[Span]], created [[Span]] becomes new current
    *
    * Unsafe because caller is responsible for properly closing returned [[AutoCloseableSpan]], yet resource operations like
    * [[useCloseableInScope]] can proper finalization
    *
    * @see
    *   [[useCloseableInScope]]
    * @return
    *   [[AutoCloseableSpan]] which can be used to finalize opened span, this is close it and restore previous current
    */
  def spanUnsafe(
      spanName: String,
      spanKind: SpanKind = SpanKind.INTERNAL,
      attributes: Attributes = Attributes.empty(),
      links: Seq[SpanContext] = Seq.empty
  ): AutoCloseableSpan =
    val previousContext = contextHolder.get()
    val builder = tracer.spanBuilder(spanName).setSpanKind(spanKind).setAllAttributes(attributes).setParent(previousContext)
    val withLinks = links.foldLeft(builder)((b, l) => b.addLink(l))
    val started = withLinks.startSpan()
    val scope = contextHolder.set(previousContext.`with`(started))

    AutoCloseableSpan.Impl(started, scope)
  end spanUnsafe

  /** Span around execution of arbitrary code
    * starts span before execution and ends it right after or in case of exception
    * opened span is made a child of current [[Span]] and becomes new current
    * on close span is closed, and previous current is restored
    *
    * for [[Flow]] specific spanning use [[OxFlowTracing.span]]
    *
    * @param beforeClose - operation to be executed right before closing opened span, in case exception was thrown, second argument will contain thrown exception, if execution of [[beforeClose]] itself throws, then exception is added as suppressed to original exception and span is closed
    */
  def byNameSpan[T](t: => T)(
      spanName: String,
      spanKind: SpanKind = SpanKind.INTERNAL,
      attributes: Attributes = Attributes.empty(),
      links: Seq[SpanContext] = Seq.empty,
      beforeClose: ((Span, Option[Throwable])) => Span = _._1
  ): T =
    var error: Throwable | Null = null
    var finalizer: AutoCloseableSpan | Null = null
    try
      val finalize = spanUnsafe(spanName, spanKind, attributes, links)
      finalizer = finalize
      t
    catch
      case e: Throwable =>
        error = e
        throw e
    finally
      try if finalizer ne null then beforeClose(finalizer.nn.span, Option(error)): Unit
      catch
        case e: Throwable =>
          if error ne null then error.nn.addSuppressed(e)
          else throw e
      finally if finalizer ne null then finalizer.nn.close()
      end try
    end try

  end byNameSpan
end OxTracing

object OxTracing:

  /** Tracing that relies on [[PropagatingVirtualThreadFactory]] and checks if its properly set
    */
  def native(tracer: Tracer): OxTracing = Impl(tracer, ContextHolder.CheckingThreadFactory)

  /** Custom tracing that leaves [[Context]] propagation to be implemented by user, for out of the box integration use [[native]] together
    * with [[PropagatingVirtualThreadFactory]]
    */
  def custom(tracer: Tracer): OxTracing = Impl(tracer, ContextHolder.Delegating)

  private final class Impl(val tracer: Tracer, val contextHolder: ContextHolder) extends OxTracing

end OxTracing
