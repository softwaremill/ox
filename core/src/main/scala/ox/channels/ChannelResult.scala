package ox.channels

import ox.channels.ChannelResult.{Closed, Done, Error, Value}

sealed trait ChannelResult[+T]:
  def map[U](f: T => U): ChannelResult[U] =
    this match
      case Value(value) => Value(f(value))
      case Done         => Done
      case Error(r)     => Error(r)

  def flatMap[U](f: T => ChannelResult[U]): ChannelResult[U] =
    this match
      case Value(value) => f(value)
      case Done         => Done
      case Error(r)     => Error(r)

  def getOrElse[U >: T](v: U): U =
    this match
      case Value(value) => value
      case _: Closed    => v

  def orThrow: T = this match
    case Done          => throw ChannelClosedException.Done()
    case Error(reason) => throw ChannelClosedException.Error(reason)
    case Value(value)  => value

  def isValue: Boolean = this match
    case _: Value[_] => true
    case _           => false
  def isClosed: Boolean = this match
    case _: Closed => true
    case _         => false

object ChannelResult:
  sealed trait Closed extends ChannelResult[Nothing]:
    def toException: Exception = this match
      case Error(reason) => ChannelClosedException.Error(reason)
      case Done          => ChannelClosedException.Done()

  case object Done extends Closed
  case class Error(reason: Option[Exception]) extends Closed
  case class Value[T](t: T) extends ChannelResult[T]

enum ChannelClosedException(reason: Option[Exception]) extends Exception:
  case Error(reason: Option[Exception]) extends ChannelClosedException(reason)
  case Done() extends ChannelClosedException(None)
