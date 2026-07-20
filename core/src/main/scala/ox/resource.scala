package ox

import scala.annotation.targetName

/** Use the given resource in the current scope. The resource is allocated using `acquire`, and released using `release` before the scope
  * completes, in reverse registration order. For concurrency scopes, release happens after all forks started within the scope have
  * completed (either successfully or with an error). Releasing is [[uninterruptible]].
  *
  * If the scope has already ended (which can only happen when using a leaked, explicitly-passed capability), the resource is acquired,
  * immediately released — so that cleanup is never lost — and an [[IllegalStateException]] is thrown.
  */
def useInScope[T](acquire: => T)(release: T => Unit)(using rs: ResourceScope): T =
  val t = acquire
  try rs.addFinalizer(() => release(t))
  catch
    case e: Throwable =>
      // the scope might have already ended (when using a leaked capability); not leaking the just-acquired resource
      try uninterruptible(release(t))
      catch case e2: Throwable => e.addSuppressed(e2)
      throw e
  t

/** Use the given resource, which implements [[AutoCloseable]], in the current scope. The resource is allocated using `acquire`, and closed
  * before the scope completes, in reverse registration order. For concurrency scopes, closing happens after all forks started within the
  * scope have completed (either successfully or with an error). Releasing is [[uninterruptible]].
  */
def useCloseableInScope[T <: AutoCloseable](c: => T)(using rs: ResourceScope): T = useInScope(c)(_.close())

/** Release the given resource, by running the `release` code block before the scope completes. For concurrency scopes, release happens
  * after all forks started within the scope have completed (either successfully or with an error). Releasing is [[uninterruptible]].
  */
def releaseAfterScope(release: => Unit)(using rs: ResourceScope): Unit = useInScope(())(_ => release)

/** Release the given resource, which implements [[AutoCloseable]], by running its `.close()` method before the scope completes. For
  * concurrency scopes, closing happens after all forks started within the scope have completed (either successfully or with an error).
  * Releasing is [[uninterruptible]].
  */
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
