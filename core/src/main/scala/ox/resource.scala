package ox

import java.util.concurrent.atomic.AtomicReference
import scala.annotation.implicitNotFound
import scala.annotation.targetName
import scala.util.NotGiven

@implicitNotFound(
  "resourceScope cannot be started here: a concurrency scope is visible, and forks started within the resource scope could outlive it. " +
    "Extract the resourceScope usage to a method which doesn't take a `using Ox` parameter."
)
opaque type NoEnclosingConcurrencyScope = Unit

object NoEnclosingConcurrencyScope:
  // in the companion, so that it's found via the implicit scope of the type, without any imports
  given noEnclosingConcurrencyScope(using NotGiven[OxUnsupervised]): NoEnclosingConcurrencyScope = ()

/** Starts a new resource scope: within the given code block `f`, resources can be registered using [[useInScope]] and
  * [[releaseAfterScope]]. They are released, in reverse registration order, once `f` completes (either successfully or with an exception).
  * Releasing is [[uninterruptible]]. A resource scope is not a concurrency scope: no forks can be started and no [[ForkLocal]] values can
  * be bound. The stdlib's analogue is `scala.util.Using.Manager`.
  *
  * Any concurrency scope ([[supervised]], [[supervisedError]], [[unsupervised]]) is also a resource scope, so within one you can register
  * resources directly. Starting a resource scope there is disallowed (verified at compile-time), because forks started in a lexically
  * visible resource scope could outlive it, using or registering resources after they've been released. For the same reason, the
  * [[ResourceScope]] capability must not leak out of the scope: registration after the scope ends throws an [[IllegalStateException]].
  *
  * Finalizers run with the [[ForkLocal]] values in effect where `resourceScope` was called — the same values the body sees. A finalizer
  * registered through a leaked capability from a nested [[ForkLocal]] binding does not see that nested binding.
  */
def resourceScope[T](f: ResourceScope ?=> T)(using NoEnclosingConcurrencyScope): T =
  val scope = new ResourceScope:
    private[ox] val finalizers = new AtomicReference[List[() => Unit]](Nil)
  val result =
    try Right(f(using scope))
    catch case e: Throwable => Left(e)
  runFinalizers(scope, result)

/** Use the given resource in the current scope. The resource is allocated using `acquire`, and released using `release` when the scope
  * completes, in reverse registration order. For concurrency scopes, release happens after all forks started within the scope have
  * completed (either successfully or with an exception). Releasing is [[uninterruptible]].
  *
  * If the scope has already ended (which can only happen when using a leaked, explicitly-passed capability), the resource is acquired,
  * immediately released — so that cleanup is never lost — and an [[IllegalStateException]] is thrown.
  */
def useInScope[T](acquire: => T)(release: T => Unit)(using rs: ResourceScope): T =
  val t = acquire
  try rs.addFinalizer(() => release(t))
  catch
    case e: Throwable =>
      try uninterruptible(release(t))
      catch case e2: Throwable => e.addSuppressed(e2)
      throw e
  t
end useInScope

/** As [[useInScope]], but the resource, which implements [[AutoCloseable]], is released using [[AutoCloseable.close()]]. */
def useCloseableInScope[T <: AutoCloseable](c: => T)(using rs: ResourceScope): T = useInScope(c)(_.close())

/** As [[useInScope]], but nothing is acquired — only the `release` code block is registered, to be run when the scope completes. */
def releaseAfterScope(release: => Unit)(using rs: ResourceScope): Unit = useInScope(())(_ => release)

/** As [[releaseAfterScope]], but closes the given [[AutoCloseable]] resource. */
def releaseCloseableAfterScope(toRelease: AutoCloseable)(using rs: ResourceScope): Unit = useInScope(())(_ => toRelease.close())

// binary-compatibility bridges for callers compiled against previous ox versions; remove in 2.0. The Scala-level
// names differ from the originals (to avoid overload ambiguity at in-package call sites), while @targetName restores
// the original JVM names, preserving linkage.

@targetName("useInScope")
private[ox] def useInScopeCompat[T](acquire: => T)(release: T => Unit)(using ox: OxUnsupervised): T =
  useInScope(acquire)(release)
@targetName("useCloseableInScope")
private[ox] def useCloseableInScopeCompat[T <: AutoCloseable](c: => T)(using ox: OxUnsupervised): T =
  useCloseableInScope(c)
@targetName("releaseAfterScope")
private[ox] def releaseAfterScopeCompat(release: => Unit)(using ox: OxUnsupervised): Unit =
  releaseAfterScope(release)
@targetName("releaseCloseableAfterScope")
private[ox] def releaseCloseableAfterScopeCompat(toRelease: AutoCloseable)(using ox: OxUnsupervised): Unit =
  releaseCloseableAfterScope(toRelease)

/** Use the given resource, acquired using `acquire` and released using `release` in the given `f` code block. Releasing is
  * [[uninterruptible]]. To use multiple resources, consider creating a [[resourceScope]] and using the [[useInScope]] method.
  */
inline def use[R, T](inline acquire: R, inline release: R => Unit)(inline f: R => T): T =
  useInterruptible(acquire, r => uninterruptible(release(r)))(f)

/** Use the given resource, acquired using `acquire` and released using `release` in the given `f` code block. Releasing might be
  * interrupted. To use multiple resources, consider creating a [[resourceScope]] and using the [[useInScope]] method.
  *
  * Equivalent to a `try`-`finally` block.
  */
inline def useInterruptible[R, T](inline acquire: R, inline release: R => Unit)(inline f: R => T): T =
  val r = acquire
  var caught: Throwable = null
  try f(r)
  catch
    case e: Throwable =>
      caught = e
      null.asInstanceOf[T]
  finally
    if caught == null then release(r)
    else
      try release(r)
      catch case e: Throwable => caught.addSuppressed(e)
      finally throw caught
  end try
end useInterruptible

/** Use the given [[AutoCloseable]] resource, acquired using `acquire` in the given `f` code block. Releasing is [[uninterruptible]]. To use
  * multiple resources, consider creating a [[resourceScope]] and using the [[useCloseableInScope]] method.
  */
inline def useCloseable[R <: AutoCloseable, T](inline acquire: R)(inline f: R => T): T = use(acquire, _.close())(f)
