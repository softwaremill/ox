package ox.channels

import ox.*

import scala.concurrent.duration.FiniteDuration

trait SourceOps[+T] { this: Source[T] =>
  def map[U](capacity: Int)(f: T => U)(using Ox): Source[U] =
    val c2 = Channel[U](capacity)
    fork {
      foreverWhile {
        receive() match
          case Left(ChannelState.Done)     => c2.done(); false
          case Left(ChannelState.Error(e)) => c2.error(e); false
          case Right(t)                    => safeSend(c2, f(t))
      }
    }
    c2

  def map[U](f: T => U)(using Ox): Source[U] = map(0)(f)

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

  def merge[U >: T](other: Source[U])(using Ox): Source[U] = merge(0)(other)
  def merge[U >: T](capacity: Int)(other: Source[U])(using Ox): Source[U] =
    val c = Channel[U](capacity)
    fork {
      foreverWhile {
        select(this, other) match
          case Left(ChannelState.Done)     => c.done(); false
          case Left(ChannelState.Error(e)) => c.error(e); false
          case Right(t)                    => safeSend(c, t)
      }
    }
    c

  def zip[U](other: Source[U])(using Ox): Source[(T, U)] = zip(0)(other)
  def zip[U](capacity: Int)(other: Source[U])(using Ox): Source[(T, U)] =
    val c = Channel[(T, U)](capacity)
    fork {
      foreverWhile {
        receive() match
          case Left(ChannelState.Done)     => c.done(); false
          case Left(ChannelState.Error(e)) => c.error(e); false
          case Right(t) =>
            other.receive() match
              case Left(ChannelState.Done)     => c.done(); false
              case Left(ChannelState.Error(e)) => c.error(e); false
              case Right(u)                    => safeSend(c, (t, u))
      }
    }
    c

  private def safeSend[U](c: Sink[U], t: => U): Boolean =
    try
      c.send(t)
      true
    catch
      case e: Exception =>
        c.error(e)
        false
}

trait SourceCompanionOps:
  def from[T](it: Iterable[T])(using Ox): Source[T] = from(1)(it)
  def from[T](capacity: Int)(it: Iterable[T])(using Ox): Source[T] = from(capacity)(it.iterator)

  def from[T](ts: T*)(using Ox): Source[T] = from(1)(ts.iterator)

  def from[T](it: => Iterator[T])(using Ox): Source[T] = from(1)(it)
  def from[T](capacity: Int)(it: => Iterator[T])(using Ox): Source[T] =
    val c = Channel[T](capacity)
    fork {
      val theIt = it
      try
        while theIt.hasNext do c.send(theIt.next())
        c.done()
      catch case e: Exception => c.error(e)
    }
    c

  def tick[T](interval: FiniteDuration, element: T = ())(using Ox): Source[T] =
    val c = Channel[T]()
    fork {
      forever {
        send_errorWhenInterrupt(c, element)
        Thread.sleep(interval.toMillis)
      }
    }
    c

  def timeout[T](interval: FiniteDuration, element: T = ())(using Ox): Source[T] =
    val c = Channel[T]()
    fork {
      Thread.sleep(interval.toMillis)
      send_errorWhenInterrupt(c, element)
      c.done()
    }
    c

  private def send_errorWhenInterrupt[T](c: Sink[T], v: T): Unit =
    try c.send(v)
    catch
      case e: InterruptedException =>
        c.error(e)
        throw e
