package ox

import scala.util.boundary
import scala.util.boundary.{Label, break}

object either:
  case class Fail[+A] private[ox] (a: A)

  inline def apply[A, T](inline body: Label[Fail[A] | T] ?=> T): Either[A, T] =
    boundary(body) match
      case Fail(a: A) => Left(a)
      case t: T       => Right(t)

  extension [A, B](inline t: Either[A, B])(using b: boundary.Label[either.Fail[A]])
    inline def value: B =
      t match
        case Left(a)  => break(either.Fail(a))
        case Right(b) => b

  extension [B](inline t: Option[B])(using b: boundary.Label[either.Fail[Unit]])
    inline def value: B =
      t match
        case None    => break(either.Fail(()))
        case Some(b) => b
