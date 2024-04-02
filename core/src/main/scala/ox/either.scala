package ox

import scala.compiletime.{error, summonFrom}
import scala.util.boundary
import scala.util.boundary.{Label, break}

object either:
  case class Fail[+A] private[ox] (a: A)

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
