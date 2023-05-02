package ox.channels

import ox.*

import scala.concurrent.duration.FiniteDuration

trait SourceOps[+T] { this: Source[T] =>
  def map[U](capacity: Int)(f: T => U)(using Ox): Source[U] =
    val c2 = Channel[U](capacity)
    fork {
      repeatWhile {
        receive() match
          case ChannelResult.Done     => c2.done(); false
          case ChannelResult.Error(r) => c2.error(r); false
          case ChannelResult.Value(t) =>
            try
              val u = f(t)
              c2.send(u).isValue
            catch
              case e: Exception =>
                c2.error(e)
                false
      }
    }
    c2

  def map[U](f: T => U)(using Ox): Source[U] = map(DefaultCapacity)(f)

  def take(n: Int)(using Ox): Source[T] = transform(_.take(n))
  def filter(f: T => Boolean)(using Ox): Source[T] = transform(_.filter(f))

  def transform[U](f: Iterator[T] => Iterator[U])(using Ox): Source[U] =
    val it = new Iterator[T]:
      private var v: Option[ChannelResult[T]] = None
      private def forceNext(): ChannelResult[T] = v match
        case None    => val temp = receive(); v = Some(temp); temp
        case Some(t) => t
      override def hasNext: Boolean = forceNext() match
        case ChannelResult.Done => false
        case _                  => true
      override def next(): T = forceNext() match
        case ChannelResult.Done     => throw new NoSuchElementException
        case e: ChannelResult.Error => throw e.toException
        case ChannelResult.Value(t) => v = None; t

    Source.fromIterator(f(it))

  def foreach(f: T => Unit): Unit =
    repeatWhile {
      receive() match
        case ChannelResult.Done     => false
        case e: ChannelResult.Error => throw e.toException
        case ChannelResult.Value(t) => f(t); true
    }

  def toList: List[T] =
    val b = List.newBuilder[T]
    foreach(b += _)
    b.result()

  def pipeTo(sink: Sink[T]): Unit =
    repeatWhile {
      receive() match
        case ChannelResult.Done     => sink.done(); false
        case ChannelResult.Error(r) => sink.error(r); false
        case ChannelResult.Value(t) => sink.send(t).isValue
    }

  def merge[U >: T](other: Source[U])(using Ox): Source[U] = merge(DefaultCapacity)(other)
  def merge[U >: T](capacity: Int)(other: Source[U])(using Ox): Source[U] =
    val c = Channel[U](capacity)
    fork {
      repeatWhile {
        select(this, other) match
          case ChannelResult.Done     => c.done(); false
          case ChannelResult.Error(r) => c.error(r); false
          case ChannelResult.Value(t) => c.send(t).isValue
      }
    }
    c

  def zip[U](other: Source[U])(using Ox): Source[(T, U)] = zip(DefaultCapacity)(other)
  def zip[U](capacity: Int)(other: Source[U])(using Ox): Source[(T, U)] =
    val c = Channel[(T, U)](capacity)
    fork {
      repeatWhile {
        receive() match
          case ChannelResult.Done     => c.done(); false
          case ChannelResult.Error(r) => c.error(r); false
          case ChannelResult.Value(t) =>
            other.receive() match
              case ChannelResult.Done     => c.done(); false
              case ChannelResult.Error(r) => c.error(r); false
              case ChannelResult.Value(u) => c.send(t, u).isValue
      }
    }
    c
}

trait SourceCompanionOps:
  def fromIterable[T](it: Iterable[T])(using Ox): Source[T] = fromIterable(DefaultCapacity)(it)
  def fromIterable[T](capacity: Int)(it: Iterable[T])(using Ox): Source[T] = fromIterator(capacity)(it.iterator)

  def fromValues[T](ts: T*)(using Ox): Source[T] = fromValues(DefaultCapacity)(ts: _*)
  def fromValues[T](capacity: Int)(ts: T*)(using Ox): Source[T] = fromIterator(capacity)(ts.iterator)

  def fromIterator[T](it: => Iterator[T])(using Ox): Source[T] = fromIterator(DefaultCapacity)(it)
  def fromIterator[T](capacity: Int)(it: => Iterator[T])(using Ox): Source[T] =
    val c = Channel[T](capacity)
    fork {
      val theIt = it
      try
        while theIt.hasNext do c.send(theIt.next())
        c.done()
      catch case e: Exception => c.error(e)
    }
    c

  def fromFork[T](f: Fork[T])(using Ox): Source[T] = fromFork(DefaultCapacity)(f)
  def fromFork[T](capacity: Int)(f: Fork[T])(using Ox): Source[T] =
    val c = Channel[T](capacity)
    fork {
      try
        c.send(f.join())
        c.done()
      catch case e: Exception => c.error(e)
    }
    c

  def iterate[T](zero: T)(f: T => T)(using Ox): Source[T] = iterate(DefaultCapacity)(zero)(f)
  def iterate[T](capacity: Int)(zero: T)(f: T => T)(using Ox): Source[T] =
    val c = Channel[T](capacity)
    fork {
      var t = zero
      try
        forever {
          send_errorWhenInterrupt(c, t)
          t = f(t)
        }
      catch case e: Exception => c.error(e)
    }
    c

  def unfold[S, T](initial: S)(f: S => Option[(T, S)])(using Ox): Source[T] = unfold(DefaultCapacity)(initial)(f)
  def unfold[S, T](capacity: Int)(initial: S)(f: S => Option[(T, S)])(using Ox): Source[T] =
    val c = Channel[T](capacity)
    fork {
      var s = initial
      try
        repeatWhile {
          f(s) match
            case Some((value, next)) =>
              send_errorWhenInterrupt(c, value)
              s = next
              true
            case None =>
              c.done()
              false
        }
      catch case e: Exception => c.error(e)
    }
    c

  def tick(interval: FiniteDuration)(using Ox): Source[Unit] = tick(DefaultCapacity)(interval, ())
  def tick[T](interval: FiniteDuration, element: T)(using Ox): Source[T] = tick(DefaultCapacity)(interval, element)
  def tick[T](capacity: Int)(interval: FiniteDuration, element: T = ())(using Ox): Source[T] =
    val c = Channel[T](capacity)
    fork {
      forever {
        send_errorWhenInterrupt(c, element)
        Thread.sleep(interval.toMillis)
      }
    }
    c

  def repeat(using Ox): Source[Unit] = repeat(DefaultCapacity)(())
  def repeat[T](element: T)(using Ox): Source[T] = repeat(DefaultCapacity)(element)
  def repeat[T](capacity: Int)(element: T = ())(using Ox): Source[T] =
    val c = Channel[T](capacity)
    fork {
      forever {
        send_errorWhenInterrupt(c, element)
      }
    }
    c

  def timeout(interval: FiniteDuration)(using Ox): Source[Unit] = timeout(DefaultCapacity)(interval, ())
  def timeout[T](interval: FiniteDuration, element: T)(using Ox): Source[T] = timeout(DefaultCapacity)(interval, element)
  def timeout[T](capacity: Int)(interval: FiniteDuration, element: T = ())(using Ox): Source[T] =
    val c = Channel[T](capacity)
    fork {
      Thread.sleep(interval.toMillis)
      send_errorWhenInterrupt(c, element)
      c.done()
    }
    c

  private def send_errorWhenInterrupt[T](c: Sink[T], v: T): Unit = // TODO: make default?
    try c.send(v)
    catch
      case e: InterruptedException =>
        c.error(e)
        throw e

private val DefaultCapacity = 0
