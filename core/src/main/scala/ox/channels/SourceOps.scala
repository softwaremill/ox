package ox.channels

import ox.*

import scala.concurrent.duration.FiniteDuration

trait SourceOps[+T] { this: Source[T] =>
  def map[U](f: T => U)(using Ox, StageCapacity): Source[U] =
    val c2 = Channel[U](summon[StageCapacity].toInt)
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

  def mapView[U](f: T => U): Source[U] = MappedSource(this, f)

  def take(n: Int)(using Ox, StageCapacity): Source[T] = transform(_.take(n))

  def filter(f: T => Boolean)(using Ox, StageCapacity): Source[T] = transform(_.filter(f))

  def transform[U](f: Iterator[T] => Iterator[U])(using Ox, StageCapacity): Source[U] =
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
        case _                  => true
      override def next(): T = forceNext() match
        case ChannelClosed.Done     => throw new NoSuchElementException
        case e: ChannelClosed.Error => throw e.toException
        case t: T @unchecked =>
          v = None
          t
    Source.fromIterator(f(it))

  def merge[U >: T](other: Source[U])(using Ox, StageCapacity): Source[U] =
    val c = Channel[U](summon[StageCapacity].toInt)
    fork {
      repeatWhile {
        select(this, other) match
          case ChannelClosed.Done     => c.done(); false
          case ChannelClosed.Error(r) => c.error(r); false
          case r: U @unchecked        => c.send(r).isValue
      }
    }
    c

  def concat[U >: T](other: Source[U])(using Ox, StageCapacity): Source[U] =
    Source.concat(List(() => this, () => other))

  def zip[U](other: Source[U])(using Ox, StageCapacity): Source[(T, U)] =
    val c = Channel[(T, U)](summon[StageCapacity].toInt)
    fork {
      repeatWhile {
        receive() match
          case ChannelClosed.Done     => c.done(); false
          case ChannelClosed.Error(r) => c.error(r); false
          case t: T @unchecked =>
            other.receive() match
              case ChannelClosed.Done     => c.done(); false
              case ChannelClosed.Error(r) => c.error(r); false
              case u: U @unchecked        => c.send(t, u).isValue
      }
    }
    c

  //

  def foreach(f: T => Unit): Unit =
    repeatWhile {
      receive() match
        case ChannelClosed.Done     => false
        case e: ChannelClosed.Error => throw e.toException
        case t: T @unchecked        => f(t); true
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
        case t: T @unchecked        => sink.send(t).isValue
    }

  def drain(): Unit = foreach(_ => ())
}

trait SourceCompanionOps:
  def fromIterable[T](it: Iterable[T])(using Ox, StageCapacity): Source[T] = fromIterator(it.iterator)

  def fromValues[T](ts: T*)(using Ox, StageCapacity): Source[T] = fromIterator(ts.iterator)

  def fromIterator[T](it: => Iterator[T])(using Ox, StageCapacity): Source[T] =
    val c = Channel[T](summon[StageCapacity].toInt)
    fork {
      val theIt = it
      try
        while theIt.hasNext do c.send(theIt.next())
        c.done()
      catch case e: Exception => c.error(e)
    }
    c

  def fromFork[T](f: Fork[T])(using Ox, StageCapacity): Source[T] =
    val c = Channel[T](summon[StageCapacity].toInt)
    fork {
      try
        c.send(f.join())
        c.done()
      catch case e: Exception => c.error(e)
    }
    c

  def iterate[T](zero: T)(f: T => T)(using Ox, StageCapacity): Source[T] =
    val c = Channel[T](summon[StageCapacity].toInt)
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

  def unfold[S, T](initial: S)(f: S => Option[(T, S)])(using Ox, StageCapacity): Source[T] =
    val c = Channel[T](summon[StageCapacity].toInt)
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

  def tick[T](interval: FiniteDuration, element: T = ())(using Ox, StageCapacity): Source[T] =
    val c = Channel[T](summon[StageCapacity].toInt)
    fork {
      forever {
        c.send(element)
        Thread.sleep(interval.toMillis)
      }
    }
    c

  def repeat[T](element: T = ())(using Ox, StageCapacity): Source[T] =
    val c = Channel[T](summon[StageCapacity].toInt)
    fork {
      forever {
        c.send(element)
      }
    }
    c

  def timeout[T](interval: FiniteDuration, element: T = ())(using Ox, StageCapacity): Source[T] =
    val c = Channel[T](summon[StageCapacity].toInt)
    fork {
      Thread.sleep(interval.toMillis)
      c.send(element)
      c.done()
    }
    c

  def concat[T](sources: Seq[() => Source[T]])(using Ox, StageCapacity): Source[T] =
    val c = Channel[T](summon[StageCapacity].toInt)
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

opaque type StageCapacity = Int

object StageCapacity:
  def apply(c: Int) = c
  extension (c: StageCapacity) def toInt: Int = c
  given default: StageCapacity = 0
