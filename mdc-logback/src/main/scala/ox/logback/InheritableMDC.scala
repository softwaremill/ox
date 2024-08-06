package ox.logback

import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.LogbackServiceProvider
import org.slf4j.spi.{MDCAdapter, SLF4JServiceProvider}
import org.slf4j.{ILoggerFactory, IMarkerFactory, Logger, LoggerFactory, MDC}
import ox.{ErrorMode, ForkLocal, Ox, OxError, OxUnsupervised, pipe, tap}

/** Provides support for MDC which is inheritable across (virtual) threads. Only MDC values set using the [[where]] method will be
  * inherited; this method also defines the scope, within which the provided MDC values are available.
  *
  * The semantics of [[MDC.put]] are unchanged: values set using this method will only be visible in the original thread. That is because
  * the "usual" [[MDC]] usage is unstructured, and we don't want to set values for the entire scope (which might exceed the calling thread).
  *
  * Internally, a [[ForkLocal]] (backed by a ScopedValue) is used, to store the scoped context.
  *
  * Prior to using inheritable MDCs, the [[init]] method has to be called. This performs some operations using the reflection API, to
  * substitute Logback's MDC support with one that is scope-aware.
  */
object InheritableMDC:
  private val logger: Logger = LoggerFactory.getLogger(getClass.getName)
  private[logback] val currentContext: ForkLocal[Option[MDCAdapter]] = ForkLocal(None)

  /** Initialise inheritable MDCs. Must be called as early in the app's code as possible. */
  lazy val init: Unit =
    // Obtaining the current provider, to replace it later with our implementation returning the correct MDCAdapter.
    val getProviderMethod = classOf[LoggerFactory].getDeclaredMethod("getProvider")
    getProviderMethod.setAccessible(true)
    getProviderMethod.invoke(null) match {
      case currentProvider: LogbackServiceProvider =>
        // Creating and setting the correct MDCAdapter on the LoggerContext; this is used internally by Logback to
        // obtain the MDC values.
        val ctx = currentProvider.getLoggerFactory.asInstanceOf[LoggerContext]
        val scopedValuedMDCAdapter = new DelegateToCurrentMDCAdapter(ctx.getMDCAdapter)
        ctx.setMDCAdapter(scopedValuedMDCAdapter)

        // Second, we need to override the provider so that its .getMDCAdapter method returns our instance. This is used
        // when setting/clearing the MDC values. Whether in a scope or not, this will delegate to the "root" MDCAdapter,
        // because of ScopedMDCAdapter's implementation.
        val providerField = classOf[LoggerFactory].getDeclaredField("PROVIDER")
        providerField.setAccessible(true)
        providerField.set(null, new OverrideMDCAdapterDelegateProvider(currentProvider, scopedValuedMDCAdapter))

        logger.info(s"Scoped-value based MDC initialized")
      case currentProvider =>
        logger.warn(s"A non-Logback SLF4J provider ($currentProvider) is being used, unable to initialize scoped-value based MDC")
    }
  end init

  /** Set the given MDC key-value mappings as passed in `kvs`, for the duration of evaluating `f`. The values will be available for any
    * forks created within `f`.
    *
    * @see
    *   Usage notes on [[ForkLocal.unsupervisedWhere()]].
    */
  def unsupervisedWhere[T](kvs: (String, String)*)(f: OxUnsupervised ?=> T): T = currentContext.unsupervisedWhere(adapterWith(kvs: _*))(f)

  /** Set the given MDC key-value mappings as passed in `kvs`, for the duration of evaluating `f`. The values will be available for any
    * forks created within `f`.
    *
    * @see
    *   Usage notes on [[ForkLocal.supervisedWhere()]].
    */
  def supervisedWhere[T](kvs: (String, String)*)(f: Ox ?=> T): T = currentContext.supervisedWhere(adapterWith(kvs: _*))(f)

  /** Set the given MDC key-value mappings as passed in `kvs`, for the duration of evaluating `f`. The values will be available for any
    * forks created within `f`.
    *
    * @see
    *   Usage notes on [[ForkLocal.supervisedErrorWhere()]].
    */
  def supervisedErrorWhere[E, F[_], U](errorMode: ErrorMode[E, F])(kvs: (String, String)*)(f: OxError[E, F] ?=> F[U]): F[U] =
    currentContext.supervisedErrorWhere(errorMode)(adapterWith(kvs: _*))(f)

  private def adapterWith(kvs: (String, String)*): Option[ScopedMDCAdapter] =
    // unwrapping the MDC adapter, so that we get the "target" one; using DelegateToCurrentMDCAdapter would lead to
    // infinite loops when delegating
    val currentAdapter = MDC.getMDCAdapter.asInstanceOf[DelegateToCurrentMDCAdapter].currentAdapter()
    Some(new ScopedMDCAdapter(Map(kvs: _*), currentAdapter))
end InheritableMDC

private class OverrideMDCAdapterDelegateProvider(delegate: SLF4JServiceProvider, mdcAdapter: MDCAdapter) extends SLF4JServiceProvider:
  override def getMDCAdapter: MDCAdapter = mdcAdapter

  override def getLoggerFactory: ILoggerFactory = delegate.getLoggerFactory
  override def getMarkerFactory: IMarkerFactory = delegate.getMarkerFactory
  override def getRequestedApiVersion: String = delegate.getRequestedApiVersion
  override def initialize(): Unit = delegate.initialize()

/** An [[MDCAdapter]] which delegates to a [[ScopedMDCAdapter]] if one is available, or falls back to the root one otherwise. */
private class DelegateToCurrentMDCAdapter(rootAdapter: MDCAdapter) extends MDCAdapter:
  def currentAdapter(): MDCAdapter = InheritableMDC.currentContext.get().getOrElse(rootAdapter)

  override def put(key: String, `val`: String): Unit = currentAdapter().put(key, `val`)
  override def get(key: String): String = currentAdapter().get(key)
  override def remove(key: String): Unit = currentAdapter().remove(key)
  override def clear(): Unit = currentAdapter().clear()
  override def getCopyOfContextMap: java.util.Map[String, String] = currentAdapter().getCopyOfContextMap
  override def setContextMap(contextMap: java.util.Map[String, String]): Unit = currentAdapter().setContextMap(contextMap)
  override def pushByKey(key: String, value: String): Unit = currentAdapter().pushByKey(key, value)
  override def popByKey(key: String): String = currentAdapter().popByKey(key)
  override def getCopyOfDequeByKey(key: String): java.util.Deque[String] = currentAdapter().getCopyOfDequeByKey(key)
  override def clearDequeByKey(key: String): Unit = currentAdapter().clearDequeByKey(key)

/** An [[MDCAdapter]] that is used within a structured scope. Stores an (immutable) map of values that are set within this scope. All other
  * operations are delegated to the parent adapter (might be either another scoped, or the root Logback, adapter).
  */
private class ScopedMDCAdapter(mdcValues: Map[String, String], delegate: MDCAdapter) extends MDCAdapter:
  override def get(key: String): String = mdcValues.getOrElse(key, delegate.get(key))
  override def getCopyOfContextMap: java.util.Map[String, String] =
    delegate.getCopyOfContextMap
      .pipe(v => if v == null then new java.util.HashMap() else new java.util.HashMap[String, String](v))
      .tap(copy => mdcValues.foreach((k, v) => copy.put(k, v)))

  override def put(key: String, `val`: String): Unit = delegate.put(key, `val`)
  override def remove(key: String): Unit = delegate.remove(key)
  override def clear(): Unit = delegate.clear()
  override def setContextMap(contextMap: java.util.Map[String, String]): Unit = delegate.setContextMap(contextMap)
  override def pushByKey(key: String, value: String): Unit = delegate.pushByKey(key, value)
  override def popByKey(key: String): String = delegate.popByKey(key)
  override def getCopyOfDequeByKey(key: String): java.util.Deque[String] = delegate.getCopyOfDequeByKey(key)
  override def clearDequeByKey(key: String): Unit = delegate.clearDequeByKey(key)
