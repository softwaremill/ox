package ox

/** Use the given resource in the current concurrency scope. The resource is allocated using `acquire`, and released after the all forks in
  * the scope complete, using `release`. Releasing is uninterruptible.
  */
def useInScope[T](acquire: => T)(release: T => Unit)(using Ox): T =
  val t = acquire
  summon[Ox].addFinalizer(() => release(t))
  t

/** Use the given resource, which implements [[AutoCloseable]], in the current concurrency scope. The resource is allocated using `acquire`,
  * and released after the all forks in the scope complete, using [[AutoCloseable.close()]]. Releasing is uninterruptible.
  */
def useCloseableInScope[T <: AutoCloseable](c: => T)(using Ox): T = useInScope(c)(_.close())
