package ox.channels

import ox.*

import scala.concurrent.duration.FiniteDuration

trait SourceOps[+T] { this: Source[T] =>
  def map[U](f: T => U)(using Ox): Source[U] = map(DefaultCapacity)(f)
  def map[U](capacity: Int)(f: T => U)(using Ox): Source[U] =
    val c2 = Channel[U](capacity)
    fork {
      repeatWhile {
        receive() match
          case ChannelClosed.Done     => c2.done(); false
          case ChannelClosed.Error(r) => c2.error(r); false
          case t: T @unchecked =>
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

  def take(n: Int)(using Ox): Source[T] = take(DefaultCapacity)(n)
  def take(capacity: Int)(n: Int)(using Ox): Source[T] = transform(capacity)(_.take(n))

  def filter(f: T => Boolean)(using Ox): Source[T] = filter(DefaultCapacity)(f)
  def filter(capacity: Int)(f: T => Boolean)(using Ox): Source[T] = transform(capacity)(_.filter(f))

  def transform[U](f: Iterator[T] => Iterator[U])(using Ox): Source[U] = transform(DefaultCapacity)(f)
  def transform[U](capacity: Int)(f: Iterator[T] => Iterator[U])(using Ox): Source[U] =
    val it = new Iterator[T]:
      private var v: Option[T | ChannelClosed] = None
      private def forceNext(): T | ChannelClosed = v match
        case None =>
          val temp = receive()
          v = Some(temp)
          temp
        case Some(t) => t
      override def hasNext: Boolean = forceNext() match
        case ChannelClosed.Done => false
        case _                        => true
      override def next(): T = forceNext() match
        case ChannelClosed.Done     => throw new NoSuchElementException
        case e: ChannelClosed.Error => throw e.toException
        case t: T @unchecked =>
          v = None
          t
    Source.fromIterator(capacity)(f(it))

  def merge[U >: T](other: Source[U])(using Ox): Source[U] = merge(DefaultCapacity)(other)
  def merge[U >: T](capacity: Int)(other: Source[U])(using Ox): Source[U] =
    val c = Channel[U](capacity)
    fork {
      repeatWhile {
        select((this: Source[U]).receiveClause, other.receiveClause) match
          case ChannelClosed.Done     => c.done(); false
          case ChannelClosed.Error(r) => c.error(r); false
          case r: Source[U]#Received        => c.send(r.value).isValue
      }
    }
    c

  def concat[U >: T](other: Source[U])(using Ox): Source[U] = concat(DefaultCapacity)(other)
  def concat[U >: T](capacity: Int)(other: Source[U])(using Ox): Source[U] = Source.concat(capacity)(List(() => this, () => other))

  def zip[U](other: Source[U])(using Ox): Source[(T, U)] = zip(DefaultCapacity)(other)
  def zip[U](capacity: Int)(other: Source[U])(using Ox): Source[(T, U)] =
    val c = Channel[(T, U)](capacity)
    fork {
      repeatWhile {
        receive() match
          case ChannelClosed.Done     => c.done(); false
          case ChannelClosed.Error(r) => c.error(r); false
          case t: T @unchecked =>
            other.receive() match
              case ChannelClosed.Done     => c.done(); false
              case ChannelClosed.Error(r) => c.error(r); false
              case u: U @unchecked              => c.send(t, u).isValue
      }
    }
    c

  //

  def foreach(f: T => Unit): Unit =
    repeatWhile {
      receive() match
        case ChannelClosed.Done     => false
        case e: ChannelClosed.Error => throw e.toException
        case t: T @unchecked              => f(t); true
    }

  def toList: List[T] =
    val b = List.newBuilder[T]
    foreach(b += _)
    b.result()

  def pipeTo(sink: Sink[T]): Unit =
    repeatWhile {
      receive() match
        case ChannelClosed.Done     => sink.done(); false
        case ChannelClosed.Error(r) => sink.error(r); false
        case t: T @unchecked              => sink.send(t).isValue
    }

  def drain(): Unit = foreach(_ => ())
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
          c.send(t)
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
              c.send(value)
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
        c.send(element)
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
        c.send(element)
      }
    }
    c

  def timeout(interval: FiniteDuration)(using Ox): Source[Unit] = timeout(DefaultCapacity)(interval, ())
  def timeout[T](interval: FiniteDuration, element: T)(using Ox): Source[T] = timeout(DefaultCapacity)(interval, element)
  def timeout[T](capacity: Int)(interval: FiniteDuration, element: T = ())(using Ox): Source[T] =
    val c = Channel[T](capacity)
    fork {
      Thread.sleep(interval.toMillis)
      c.send(element)
      c.done()
    }
    c

  def concat[T](sources: Seq[() => Source[T]])(using Ox): Source[T] = concat(DefaultCapacity)(sources)
  def concat[T](capacity: Int)(sources: Seq[() => Source[T]])(using Ox): Source[T] =
    val c = Channel[T](capacity)
    fork {
      var currentSource: Option[Source[T]] = None
      val sourcesIterator = sources.iterator
      var continue = true
      try
        while continue do
          currentSource match
            case None if sourcesIterator.hasNext => currentSource = Some(sourcesIterator.next()())
            case None =>
              c.done()
              continue = false
            case Some(source) =>
              source.receive() match
                case ChannelClosed.Done =>
                  currentSource = None
                case ChannelClosed.Error(r) =>
                  c.error(r)
                  continue = false
                case t: T @unchecked =>
                  c.send(t)
      catch case e: Exception => c.error(e)
    }
    c

private val DefaultCapacity = 0
