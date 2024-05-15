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
  */
trait IO

object IO:
  /** Grants the [[IO]] capability when executing the given block of code. Ideally should only be used at the edges of your application
    * (e.g. in the `main` method), or when integrating with third-party libraries.
    */
  def unsafe[T](f: IO ?=> T): T = f(using new IO {})
