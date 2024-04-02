package ox

import scala.compiletime.{error, summonFrom}
import scala.util.boundary
import scala.util.boundary.{Label, break}

object either:
  case class Fail[+A] private[ox] (a: A)

  /** Within an [[either]] block, allows unwrapping [[Either]] and [[Option]] values using [[value]]. The result is the right-value of an
    * `Either`, or the defined-value of the `Option`. In case a failure is encountered (a left-value of an `Either`, or a `None`), the
    * computation is short-circuited and the failure becomes the result.
    *
    * Uses the [[boundary]]-break mechanism.
    *
    * @param body
    *   The code block, within which [[Either]]s and [[Option]]s can be unwrapped using [[value]].
    * @tparam A
    *   The error type.
    * @tparam T
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
    *       v1.value ++ v2.value
    *   }}}
    */
  inline def apply[A, T](inline body: Label[Fail[A] | T] ?=> T): Either[A, T] =
    boundary(body) match
      case Fail(a: A) => Left(a)
      case t: T       => Right(t)

  extension [A, B](inline t: Either[A, B])
    transparent inline def value: B =
      summonFrom {
        case given boundary.Label[either.Fail[A]] =>
          t match
            case Left(a)  => break(either.Fail(a))
            case Right(b) => b
        case given boundary.Label[either.Fail[Nothing]] =>
          error("The enclosing `either` call uses a different error type.\nIf it's explicitly typed, is the error type correct?")
        case _ => error("`.value` can only be used within an `either` call.\nIs it present?")
      }

  extension [B](inline t: Option[B])(using b: boundary.Label[either.Fail[Unit]])
    transparent inline def value: B =
      summonFrom {
        case given boundary.Label[either.Fail[Unit]] =>
          t match
            case None    => break(either.Fail(()))
            case Some(b) => b
        case given boundary.Label[either.Fail[Nothing]] =>
          error(
            "The enclosing `either` call uses a different error type.\nIf it's explicitly typed, is the error type correct?\nNote that for options, the error type must contain a `Unit`."
          )
        case _ => error("`.value` can only be used within an `either` call.\nIs it present?")
      }
