package ox

/** Use the given resource in the current concurrency scope. The resource is allocated using `acquire`, and released after the all forks in
  * the scope complete (either successfully or with an error), using `release`. Releasing is [[uninterruptible]].
  */
def useInScope[T](acquire: => T)(release: T => Unit)(using OxUnsupervised): T =
  val t = acquire
  summon[OxUnsupervised].addFinalizer(() => release(t))
  t

/** Use the given resource, which implements [[AutoCloseable]], in the current concurrency scope. The resource is allocated using `acquire`,
  * and released after the all forks in the scope complete (either successfully or with an error), using [[AutoCloseable.close()]].
  * Releasing is [[uninterruptible]].
  */
def useCloseableInScope[T <: AutoCloseable](c: => T)(using OxUnsupervised): T = useInScope(c)(_.close())

/** Release the given resource, by running the `release` code block. Releasing is done after all the forks in the scope complete (either
  * successfully or with an error), but before the current concurrency scope completes. Releasing is [[uninterruptible]].
  */
def releaseAfterScope(release: => Unit)(using OxUnsupervised): Unit = useInScope(())(_ => release)

/** Release the given resource, which implements [[AutoCloseable]], by running its `.close()` method. Releasing is done after all the forks
  * in the scope complete (either successfully or with an error), but before the current concurrency scope completes. Releasing is
  * [[uninterruptible]].
  */
def releaseCloseableAfterScope(toRelease: AutoCloseable)(using OxUnsupervised): Unit = useInScope(())(_ => toRelease.close())

/** Use the given resource, acquired using `acquire` and released using `release` in the given `f` code block. Releasing is
  * [[uninterruptible]]. To use multiple resources, consider creating a [[supervised]] scope and [[useInScope]] method.
  */
inline def use[R, T](inline acquire: R, inline release: R => Unit)(inline f: R => T): T =
  useInterruptible(acquire, r => uninterruptible(release(r)))(f)

/** Use the given resource, acquired using `acquire` and released using `release` in the given `f` code block. Releasing might be
  * interrupted. To use multiple resources, consider creating a [[supervised]] scope and [[useInScope]] method.
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
  * multiple resources, consider creating a [[supervised]] scope and [[useCloseableInScope]] method.
  */
inline def useCloseable[R <: AutoCloseable, T](inline acquire: R)(inline f: R => T): T = use(acquire, _.close())(f)
