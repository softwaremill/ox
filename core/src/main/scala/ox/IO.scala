package ox

/** Represents a capability to perform I/O operations. The capability should be part of the signature of any method, which either performs
  * I/O operations, or calls other methods which perform I/O operations. For example:
  *
  * {{{
  *   def readFromFile(path: String)(using IO): String = ...
  *   def writeToFile(path: String, content: String)(using IO): Unit = ...
  *   def transform(path: String)(f: String => String)(using IO): Unit =
  *     writeToFile(path, f(readFromFile(path))
  * }}}
  *
  * The capability can be introduced using [[IO.unsafe]] or by `import ox.IO.globalForTesting.given`.
  *
  * Take care not to capture the capability e.g. using constructors (unless you are sure such usage is safe), as this might circumvent the
  * tracking of I/O operations. Similarly, the capability might be captured by lambdas, which might later be used when the IO capability is
  * not in scope. In future Scala and Ox releases, these problems should be detected at compile-time using the upcoming capture checker.
  */
class IO private[ox] ()

object IO:
  private val IOInstance = new IO

  /** Grants the [[IO]] capability when executing the given block of code. Ideally should **only** be used:
    *   - at the edges of your application (e.g. in the `main` method)
    *   - when integrating with third-party libraries
    *
    * In tests, use [[globalForTesting]].
    */
  inline def unsafe[T](f: IO ?=> T): T = f(using IOInstance)

  /** When imported using `import ox.IO.globalForTesting.given`, grants the [[IO]] capability globally within the scope of the import.
    * Designed to be used in tests.
    */
  object globalForTesting:
    given IO = IOInstance
