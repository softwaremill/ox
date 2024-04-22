package ox

import scala.compiletime.{error, summonFrom}
import scala.util.boundary
import scala.util.boundary.{Label, break}

object either:
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
  inline def apply[E, A](inline body: Label[Either[E, A]] ?=> A): Either[E, A] = boundary(Right(body))

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

  extension [E](e: E)
    transparent inline def fail(): Nothing =
      summonFrom {
        case given boundary.Label[Either[E, Nothing]] => break(Left(e))
        case given boundary.Label[Either[Nothing, Nothing]] =>
          error("The enclosing `either` call uses a different error type.\nIf it's explicitly typed, is the error type correct?")
      }
