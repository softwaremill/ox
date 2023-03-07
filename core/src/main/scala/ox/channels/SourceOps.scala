package ox.channels

import ox.Ox
import ox.Ox.*

trait SourceOps[+T] { this: Source[T] =>
  def map[U](capacity: Int)(f: T => U)(using Ox): Source[U] =
    val c2 = Channel[U](capacity)
    fork {
      foreverWhile {
        receive() match
          case Left(ChannelState.Done)     => c2.done(); false
          case Left(ChannelState.Error(e)) => c2.error(e); false
          case Right(t) =>
            try
              c2.send(f(t))
              true
            catch
              case _: InterruptedException => c2.done(); false
              case e: Exception            => c2.error(e); false
      }
    }
    c2

  def map[U](f: T => U)(using Ox): Source[U] = map(1)(f)

  def transform[U](f: Iterator[T] => Iterator[U])(using Ox): Source[U] =
    val it = new Iterator[T]:
      private var v: Option[ClosedOr[T]] = None
      private def forceNext(): ClosedOr[T] = v match
        case None    => val temp = receive(); v = Some(temp); temp
        case Some(t) => t
      override def hasNext: Boolean = forceNext() match
        case Left(ChannelState.Done) => false
        case _                       => true
      override def next(): T = forceNext() match
        case Left(ChannelState.Done)     => throw new NoSuchElementException
        case Left(e: ChannelState.Error) => throw e.toException
        case Right(t)                    => v = None; t

    Source.from(f(it))

  def foreach(f: T => Unit): Unit =
    foreverWhile {
      receive() match
        case Left(ChannelState.Done)     => false
        case Left(s: ChannelState.Error) => throw s.toException
        case Right(t)                    => f(t); true
    }

  def toList: List[T] =
    val b = List.newBuilder[T]
    foreach(b += _)
    b.result()
}

trait SourceCompanionOps:
  def from[T](it: Iterable[T])(using Ox): Source[T] = from(1)(it)
  def from[T](capacity: Int)(it: Iterable[T])(using Ox): Source[T] = from(capacity)(it.iterator)

  def from[T](it: => Iterator[T])(using Ox): Source[T] = from(1)(it)
  def from[T](capacity: Int)(it: => Iterator[T])(using Ox): Source[T] =
    val c = Channel[T](capacity)
    fork {
      val theIt = it
      try
        while theIt.hasNext do c.send(theIt.next())
        c.done()
      catch
        case _: InterruptedException => c.done()
        case e: Exception            => c.error(e)
    }
    c
