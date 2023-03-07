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

  def foreach(f: T => Unit): Unit =
    foreverWhile {
      receive() match
        case Left(ChannelState.Done)     => false
        case Left(s: ChannelState.Error) => throw s.toException
        case Right(t)                    => f(t); true
    }
}
