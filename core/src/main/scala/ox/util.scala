package ox

import scala.util.control.NonFatal

extension [T](inline t: T)
  /** Discard the result of the computation, to avoid discarded non-unit value warnings.
    *
    * @example
    *   {{{
    *   import ox.discard
    *   someMethodCall().another().discard
    *   }}}
    */
  // noinspection UnitMethodIsParameterless
  inline def discard: Unit =
    val _ = t
    ()

  /** Run the provided callback when an exception occurs, rethrowing the original one after the callback completes.
    *
    * If `f` itself throws an exception, this other exception will be added as suppressed to the original one.
    *
    * @example
    *   {{{
    *   import ox.tapException
    *   someMethodCall().tapException { e => logger.error("Exception occurred", e) }
    *   }}}
    */
  inline def tapException(inline f: Throwable => Unit): T =
    try t
    catch
      case e: Throwable =>
        try f(e)
        catch case ee: Throwable => e.addSuppressed(ee)
        throw e

  /** Same as [[tapException()]], but runs the callback only for non-fatal exceptions. */
  inline def tapNonFatalException(inline f: Throwable => Unit): T =
    try t
    catch
      case NonFatal(e) =>
        try f(e)
        catch case ee: Throwable => e.addSuppressed(ee)
        throw e

/** Prevent `f` from being interrupted. Any interrupted exceptions that occur while evaluating `f` will be re-thrown once it completes. */
inline def uninterruptible[T](inline f: T): T =
  scoped {
    val t = fork(f)

    def joinDespiteInterrupted: T =
      try t.join()
      catch
        case e: InterruptedException =>
          joinDespiteInterrupted
          throw e

    joinDespiteInterrupted
  }
