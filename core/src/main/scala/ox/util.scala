package ox

import scala.concurrent.{Await, Future}
import scala.concurrent.duration.*
import scala.util.control.NonFatal
import scala.quoted.*

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

  /** Pipe the value of this expression into the provided function, returning the result of the function. Useful for chaining operations.
    *
    * @example
    *   {{{
    *   import ox.pipe
    *   someMethodCall().pipe { result => transform(result) }
    *   }}}
    * @see
    *   [[scala.util.ChainingOps.pipe]] for a non-inline version in the standard library
    * @param f
    *   The function to apply to the value of this expression.
    * @return
    *   The result of applying `f` to the value of this expression.
    */
  inline def pipe[U](inline f: T => U): U =
    f(t)

  /** Apply `f` to the value of this expression, returning the original value of the expression. Useful for side-effecting operations.
    *
    * @example
    *   {{{
    *   import ox.tap
    *   someMethodCall().tap { result => log(result) }
    *   }}}
    * @see
    *   [[scala.util.ChainingOps.tap]] for a non-inline version in the standard library
    * @param f
    *   The function to apply to the value of this expression.
    * @return
    *   The original value of this expression.
    */
  inline def tap(inline f: T => Unit): T =
    val tt = t
    f(tt)
    tt

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
end extension

extension [T](inline f: Future[T])
  /** Block the current thread/fork until the future completes. Returns the successful value of the future, or throws the exception, with
    * which it failed.
    */
  inline def get(): T = Await.result(f, Duration.Inf)

/** Prevent `f` from being interrupted. Any interrupted exceptions that occur while evaluating `f` will be re-thrown once it completes. */
inline def uninterruptible[T](inline f: T): T =
  unsupervised {
    val t = forkUnsupervised(f)

    def joinDespiteInterrupted: T =
      try t.join()
      catch
        case e: InterruptedException =>
          joinDespiteInterrupted.discard
          throw e

    joinDespiteInterrupted
  }

/** Sleep (block the current thread/fork) for the provided amount of time. */
inline def sleep(inline howLong: Duration): Unit = Thread.sleep(howLong.toMillis)

/** Provide duration and result for operation. */
inline def timed[T](operation: => T): (FiniteDuration, T) =
  val before = System.nanoTime()
  val result = operation
  val after = System.nanoTime()
  val duration = (after - before).nanos
  (duration, result)

/** Prints the code and result of the expression to the standard output. Equivalent to `println(xAsCode + " = " + x)`.
  *
  * @example
  *   {{{
  *   val a = "x"
  *   debug(a.toUpperCase + "y")
  *
  *   // prints: a.toUpperCase().+("y") = Xy
  *   }}}
  */
inline def debug[T](inline x: T): Unit = ${ debugImpl('x) }
private def debugImpl[T: Type](x: Expr[T])(using qctx: Quotes): Expr[Unit] =
  import qctx.reflect.*
  val xAsCode = x.asTerm.show(using Printer.TreeShortCode)
  '{ println(${ Expr(xAsCode) } + " = " + $x) }
