package ox.channels

import scala.util.{Failure, Success, Try}

/** Extension methods on union types which includes [[ChannelClosed]]. */
object ChannelClosedUnion:

  extension [T](v: T | ChannelClosed)
    inline def map[U](f: T => U): U | ChannelClosed = v match
      case ChannelClosed.Done     => ChannelClosed.Done
      case e: ChannelClosed.Error => e
      case t: T @unchecked        => f(t)

    /** Throw a [[ChannelClosedException]] if the provided value represents a closed channel (one of [[ChannelClosed]] values). */
    inline def orThrow: T = v match
      case c: ChannelClosed => throw c.toThrowable
      case t: T @unchecked  => t

    inline def toEither: Either[ChannelClosed, T] = v match
      case c: ChannelClosed => Left(c)
      case t: T @unchecked  => Right(t)

    inline def toTry: Try[T] = v match
      case c: ChannelClosed => Failure(c.toThrowable)
      case t: T @unchecked  => Success(t)

    inline def isValue: Boolean = v match
      case _: ChannelClosed => false
      case _: T @unchecked  => true
  end extension

  extension [T](v: T | ChannelClosed.Error)(using DummyImplicit)
    inline def mapUnlessError[U](f: T => U): U | ChannelClosed.Error = v match
      case e: ChannelClosed.Error => e
      case t: T @unchecked        => f(t)
end ChannelClosedUnion
