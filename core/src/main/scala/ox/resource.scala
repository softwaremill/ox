package ox

/** Use the given resource in the current concurrency scope. The resource is allocated using `acquire`, and released after the all forks in
  * the scope complete (either successfully or with an error), using `release`. Releasing is uninterruptible.
  */
def useInScope[T](acquire: => T)(release: T => Unit)(using Ox): T =
  val t = acquire
  summon[Ox].addFinalizer(() => release(t))
  t

/** Use the given resource, which implements [[AutoCloseable]], in the current concurrency scope. The resource is allocated using `acquire`,
  * and released after the all forks in the scope complete (either successfully or with an error), using [[AutoCloseable.close()]].
  * Releasing is uninterruptible.
  */
def useCloseableInScope[T <: AutoCloseable](c: => T)(using Ox): T = useInScope(c)(_.close())

/** Release the given resource, by running the `release` code block. Releasing is done after all the forks in the scope complete (either
  * successfully or with an error), but before the current concurrency scope completes. Releasing is uninterruptible.
  */
def releaseAfterScope(release: => Unit)(using Ox): Unit = useInScope(())(_ => release)

/** Release the given resource, which implements [[AutoCloseable]], by running its `.close()` method. Releasing is done after all the forks
  * in the scope complete (either successfully or with an error), but before the current concurrency scope completes. Releasing is
  * uninterruptible.
  */
def releaseCloseableAfterScope(toRelease: AutoCloseable)(using Ox): Unit = useInScope(())(_ => toRelease.close())
