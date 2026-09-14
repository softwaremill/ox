package ox

import ox.internal.ResourceRuntime

import java.util.concurrent.atomic.AtomicReference
import scala.annotation.implicitNotFound
import scala.util.NotGiven

/** Package-visible bridge used by public inline helpers, whose expansion cannot refer to the package-private runtime object directly. */
private[ox] def runResourceCleanup[T](f: => T): T = ResourceRuntime.uninterruptible(f)

/** Capability granted by a [[resourceScope]] and, via subtyping, by concurrency scopes which extend [[ResourceScope.Concurrent]].
  *
  * Represents a capability to register resources (e.g. using [[useInScope]] or [[releaseAfterScope]]) to be released when the scope
  * completes. Does not by itself allow forking.
  */
@implicitNotFound(
  "This operation must be run within a `resourceScope`, or a compatible concurrency scope. " +
    "Alternatively, require that the enclosing method is run within a scope by adding a `using ResourceScope` parameter list."
)
trait ResourceScope:
  // Contains null once the scope's finalizers have been run; registration then throws (see addFinalizer).
  private[ox] def finalizers: AtomicReference[List[() => Unit]]

  private[ox] def addFinalizer(f: () => Unit): Unit =
    val _ = finalizers.updateAndGet {
      case null => throw new IllegalStateException("Cannot register a resource: the scope to which it would be attached has already ended")
      case fs   => f :: fs
    }
end ResourceScope

object ResourceScope:
  /** Marker for resource scopes which also provide concurrency capabilities. Used to prevent a dedicated [[resourceScope]] from being
    * started where a lexically visible concurrency scope could be used to start forks which outlive it.
    */
  trait Concurrent extends ResourceScope:
    /** Backend hook used by concurrency scopes to preserve their context while cleanup runs. */
    private[ox] def runUninterruptibly[T](f: => T): T = ResourceRuntime.onVirtualThread(f)
end ResourceScope

@implicitNotFound(
  "resourceScope cannot be started here: a concurrency scope is visible, and forks started within the resource scope could outlive it. " +
    "Extract the resourceScope usage to a method which doesn't take a concurrency-scope capability parameter."
)
opaque type NoEnclosingConcurrencyScope = Unit

object NoEnclosingConcurrencyScope:
  // In the companion, so that it's found via the implicit scope of the type, without any imports.
  given noEnclosingConcurrencyScope(using NotGiven[ResourceScope.Concurrent]): NoEnclosingConcurrencyScope = ()

/** Starts a new resource scope: within the given code block `f`, resources can be registered using [[useInScope]] and
  * [[releaseAfterScope]]. They are released, in reverse registration order, once `f` completes (either successfully or with an exception).
  * Releasing runs to completion even if the waiting thread is interrupted. A resource scope is not a concurrency scope: no forks can be
  * started and no `ForkLocal` values can be bound. The standard-library analogue is `scala.util.Using.Manager`.
  *
  * Any Ox concurrency scope is also a resource scope, so within one resources can be registered directly. Starting a resource scope there
  * is disallowed (verified at compile time), because forks started using a lexically visible concurrency capability could outlive it, using
  * or registering resources after they have been released. For the same reason, the [[ResourceScope]] capability must not leak out of the
  * scope: registration after the scope ends throws an [[IllegalStateException]].
  *
  * When used with Ox Core, finalizers run with the `ForkLocal` values in effect where `resourceScope` was called — the same values the body
  * sees. A finalizer registered through a leaked capability from a nested `ForkLocal` binding does not see that nested binding.
  */
def resourceScope[T](f: ResourceScope ?=> T)(using NoEnclosingConcurrencyScope): T =
  val scope = new ResourceScope:
    private[ox] val finalizers = new AtomicReference[List[() => Unit]](Nil)

  val result =
    try Right(f(using scope))
    catch case e: Throwable => Left(e)

  ResourceRuntime.runFinalizers(scope, result)
end resourceScope

/** Use the given resource in the current scope. The resource is allocated using `acquire`, and released using `release` when the scope
  * completes, in reverse registration order. For concurrency scopes, release happens after all forks started within the scope have
  * completed (either successfully or with an exception). Releasing runs to completion even if the waiting thread is interrupted.
  *
  * If the scope has already ended (which can only happen when using a leaked, explicitly passed capability), the resource is acquired and
  * immediately released — so that cleanup is never lost — and an [[IllegalStateException]] is thrown.
  */
def useInScope[T](acquire: => T)(release: T => Unit)(using rs: ResourceScope): T =
  val resource = acquire
  try rs.addFinalizer(() => release(resource))
  catch
    case e: Throwable =>
      try runResourceCleanup(release(resource))
      catch case releaseError: Throwable => e.addSuppressed(releaseError)
      throw e
  resource
end useInScope

/** As [[useInScope]], but releases the resource using [[AutoCloseable.close()]]. */
def useCloseableInScope[T <: AutoCloseable](acquire: => T)(using ResourceScope): T = useInScope(acquire)(_.close())

/** Registers `release` to run when the current scope completes. */
def releaseAfterScope(release: => Unit)(using ResourceScope): Unit = useInScope(())(_ => release)

/** Registers the given resource to be closed when the current scope completes. */
def releaseCloseableAfterScope(toRelease: AutoCloseable)(using ResourceScope): Unit = useInScope(())(_ => toRelease.close())

/** Use the given resource, acquired using `acquire` and released using `release` in the given `f` code block. Releasing runs to completion
  * even if the waiting thread is interrupted. To use multiple resources, consider creating a [[resourceScope]] and using [[useInScope]].
  */
inline def use[R, T](inline acquire: R, inline release: R => Unit)(inline f: R => T): T =
  useInterruptible(acquire, r => runResourceCleanup(release(r)))(f)

/** Use the given resource, acquired using `acquire` and released using `release` in the given `f` code block. Releasing might be
  * interrupted. To use multiple resources, consider creating a [[resourceScope]] and using [[useInScope]].
  *
  * Equivalent to a `try`-`finally` block.
  */
inline def useInterruptible[R, T](inline acquire: R, inline release: R => Unit)(inline f: R => T): T =
  val resource = acquire
  var caught: Throwable = null
  try f(resource)
  catch
    case e: Throwable =>
      caught = e
      null.asInstanceOf[T]
  finally
    if caught == null then release(resource)
    else
      try release(resource)
      catch case e: Throwable => caught.addSuppressed(e)
      finally throw caught
  end try
end useInterruptible

/** Use the given [[AutoCloseable]] resource, acquired using `acquire` in the given `f` code block. Releasing runs to completion even if the
  * waiting thread is interrupted. To use multiple resources, consider creating a [[resourceScope]] and using [[useCloseableInScope]].
  */
inline def useCloseable[R <: AutoCloseable, T](inline acquire: R)(inline f: R => T): T = use(acquire, _.close())(f)
