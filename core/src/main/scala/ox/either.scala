package ox

import scala.annotation.implicitNotFound
import scala.compiletime.{error, summonFrom}
import scala.util.{NotGiven, boundary}
import scala.util.boundary.{Label, break}
import scala.util.control.NonFatal

object either:

  /** Catches non-fatal exceptions that occur when evaluating `t` and returns them as the left side of the returned `Either`. */
  inline def catching[T](inline t: Label[Either[Throwable, T]] ?=> T): Either[Throwable, T] =
    try boundary(Right(t))
    catch case NonFatal(e) => Left(e)

  private type NotNested = NotGiven[Label[Either[Nothing, Nothing]]]

  /** Within an [[either]] block, allows unwrapping [[Either]] and [[Option]] values using [[ok()]]. The result is the right-value of an
    * `Either`, or the defined-value of the `Option`. In case a failure is encountered (a left-value of an `Either`, or a `None`), the
    * computation is short-circuited and the failure becomes the result. Failures can also be reported using [[fail()]].
    *
    * Uses the [[boundary]]-break mechanism.
    *
    * @param body
    *   The code block, within which [[Either]]s and [[Option]]s can be unwrapped using [[ok()]]. Failures can be reported using [[fail()]].
    *   Both [[ok()]] and [[fail()]] are extension methods.
    * @tparam E
    *   The error type.
    * @tparam A
    *   The success type.
    * @return
    *   The result - either an error, or success - represented as an [[Either]]. The error type can be set to the union of all error types
    *   that are used. In case of options, failure type is assumed to be `Unit`.
    * @example
    *   {{{
    *   val v1: Either[Int, String] = ???
    *   val v2: Either[Long, String] = ???
    *
    *   val result: Either[Int | Long, String] =
    *     either:
    *       v1.ok() ++ v2.ok()
    *   }}}
    */
  inline def apply[E, A](inline body: Label[Either[E, A]] ?=> A)(using
      @implicitNotFound(
        "Nesting of either blocks is not allowed as it's error prone, due to type inference. Consider extracting the nested either block to a separate function."
      ) nn: NotNested
  ): Either[E, A] = boundary(Right(body))

  extension [E, A](inline t: Either[E, A])
    /** Unwrap the value of the `Either`, short-circuiting the computation to the enclosing [[either]], in case this is a left-value. */
    transparent inline def ok(): A =
      summonFrom {
        case given boundary.Label[Either[E, Nothing]] =>
          t match
            case Left(e)  => break(Left(e))
            case Right(a) => a
        case given boundary.Label[Either[Nothing, Nothing]] =>
          error("The enclosing `either` call uses a different error type.\nIf it's explicitly typed, is the error type correct?")
        case _ => error("`.ok()` can only be used within an `either` call.\nIs it present?")
      }

  /** Specialized extensions for Right & Left are necessary to prevent compile-time warning about unreachable cases in inlined pattern
    * matches when call site has a specific type.
    */
  extension [E, A](inline t: Right[E, A])
    /** Unwrap the value of the `Either`, returning value of type `A` on guaranteed `Right` case. */
    transparent inline def ok(): A = t.value

  extension [E, A](inline t: Left[E, A])
    /** Unwrap the value of the `Either`, short-circuiting the computation to the enclosing [[either]]. */
    transparent inline def ok(): A =
      summonFrom {
        case given boundary.Label[Either[E, Nothing]] =>
          break(t.asInstanceOf[Either[E, Nothing]])
        case given boundary.Label[Either[Nothing, Nothing]] =>
          error("The enclosing `either` call uses a different error type.\nIf it's explicitly typed, is the error type correct?")
        case _ => error("`.ok()` can only be used within an `either` call.\nIs it present?")
      }

  extension [A](inline t: Option[A])
    /** Unwrap the value of the `Option`, short-circuiting the computation to the enclosing [[either]], in case this is a `None`. */
    transparent inline def ok(): A =
      summonFrom {
        case given boundary.Label[Either[Unit, Nothing]] =>
          t match
            case None    => break(Left(()))
            case Some(a) => a
        case given boundary.Label[Either[Nothing, Nothing]] =>
          error(
            "The enclosing `either` call uses a different error type.\nIf it's explicitly typed, is the error type correct?\nNote that for options, the error type must contain a `Unit`."
          )
        case _ => error("`.ok()` can only be used within an `either` call.\nIs it present?")
      }

  /** Specialized extensions for Some & None are necessary to prevent compile-time warning about unreachable cases in inlined pattern
    * matches when call site has a specific type.
    */
  extension [A](inline t: Some[A])
    /** Unwrap the value of the `Option`, returning value of type `A` on guaranteed `Some` case. */
    transparent inline def ok(): A = t.value

  extension [A](inline t: None.type)
    /** Unwrap the value of the `Option`, short-circuiting the computation to the enclosing [[either]] on guaranteed `None`. */
    transparent inline def ok(): A =
      summonFrom {
        case given boundary.Label[Either[Unit, Nothing]] => break(Left(()))
        case given boundary.Label[Either[Nothing, Nothing]] =>
          error(
            "The enclosing `either` call uses a different error type.\nIf it's explicitly typed, is the error type correct?\nNote that for options, the error type must contain a `Unit`."
          )
        case _ => error("`.ok()` can only be used within an `either` call.\nIs it present?")
      }

  extension [E, A](inline f: Fork[Either[E, A]])
    /** Join the fork and unwrap the value of its `Either` result, short-circuiting the computation to the enclosing [[either]], in case
      * this is a left-value.
      *
      * If the fork fails with an exception, the enclosing scope ends (when the fork was started with [[fork]]). Exceptions are re-thrown by
      * this method only when the fork is started using [[forkUnsupervised]] or [[forkCancellable]].
      */
    transparent inline def ok(): A = f.join().ok()

  extension [E](e: E)
    /** Fail the computation, short-circuiting to the enclosing [[either]] block. */
    transparent inline def fail(): Nothing =
      summonFrom {
        case given boundary.Label[Either[E, Nothing]] => break(Left(e))
        case given boundary.Label[Either[Nothing, Nothing]] =>
          error("The enclosing `either` call uses a different error type.\nIf it's explicitly typed, is the error type correct?")
      }

  extension [E <: Throwable, T](e: Either[E, T])
    /** Unwrap the right-value of the `Either`, throwing the contained exception if this is a lef-value. For a variant which allows
      * unwrapping `Either`s, propagates errors and doesn't throw exceptions, see [[apply]].
      */
    def orThrow: T = e match
      case Right(value)    => value
      case Left(throwable) => throw throwable
