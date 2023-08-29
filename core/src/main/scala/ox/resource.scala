package ox

/** Use the given resource in the current scope. The resource is allocated using `acquire`, and released after the scope is done using
  * `release`. Releasing is uninterruptible.
  */
def useInScope[T](acquire: => T)(release: T => Unit)(using Ox): T =
  val t = acquire
  summon[Ox].addFinalizer(() => release(t))
  t

def useCloseableInScope[T <: AutoCloseable](c: => T)(using Ox): T = useInScope(c)(_.close())

def useScoped[T, U](acquire: => T)(release: T => Unit)(b: T => U): U = scoped(b(useInScope(acquire)(release)))
def useScoped[T <: AutoCloseable, U](acquire: => T)(b: T => U): U = scoped(b(useInScope(acquire)(_.close())))

def useSupervised[T, U](acquire: => T)(release: T => Unit)(b: T => U): U = supervised(b(useInScope(acquire)(release)))
def useSupervised[T <: AutoCloseable, U](acquire: => T)(b: T => U): U = supervised(b(useInScope(acquire)(_.close())))
