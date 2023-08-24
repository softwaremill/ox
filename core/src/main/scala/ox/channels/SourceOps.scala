package ox.channels

import ox.*

import java.util.concurrent.{ArrayBlockingQueue, ConcurrentLinkedQueue, CountDownLatch, LinkedBlockingQueue, Semaphore}
import scala.concurrent.duration.FiniteDuration

trait SourceOps[+T] { this: Source[T] =>
  // view ops (lazy)

  def mapAsView[U](f: T => U): Source[U] = CollectSource(this, t => Some(f(t)))
  def filterAsView(f: T => Boolean): Source[T] = CollectSource(this, t => if f(t) then Some(t) else None)
  def collectAsView[U](f: PartialFunction[T, U]): Source[U] = CollectSource(this, f.lift)

  // run ops (eager)

  def map[U](f: T => U)(using Ox, StageCapacity): Source[U] =
    val c2 = StageCapacity.newChannel[U]
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
              case t: Throwable =>
                c2.error(t)
                false
      }
    }
    c2

  /** Applies the given mapping function `f` to each element received from this source, and sends the results to the returned channel. At
    * most `parallelism` invocations of `f` are run in parallel.
    *
    * The mapped results are sent to the returned channel in the same order, in which inputs are received from this source. In other words,
    * ordering is preserved.
    *
    * Errors from this channel are propagated to the returned channel. Any exceptions that occur when invoking `f` are propagated as errors
    * to the returned channel as well, and result in interrupting any mappings that are in progress.
    *
    * Must be run within a scope, as child forks are created, which receive from this source, send to the resulting one, and run the
    * mappings.
    *
    * @param parallelism
    *   An upper bound on the number of forks that run in parallel. Each fork runs the function `f` on a single element of the source.
    * @param f
    *   The mapping function.
    * @return
    *   A source, onto which results of the mapping function will be sent.
    */
  def mapPar[U](parallelism: Int)(f: T => U)(using Ox, StageCapacity): Source[U] =
    val c2 = StageCapacity.newChannel[U]
    fork(mapParScope(parallelism, c2, f))
    c2

  private def mapParScope[U](parallelism: Int, c2: Channel[U], f: T => U): Unit =
    val s = new Semaphore(parallelism)
    val inProgress = Channel[Fork[U]](parallelism)
    val closeScope = new CountDownLatch(1)
    scoped {
      // enqueueing fork
      fork {
        repeatWhile {
          s.acquire()
          receive() match
            case ChannelClosed.Done =>
              inProgress.done()
              false
            case e @ ChannelClosed.Error(r) =>
              c2.error(r)
              // closing the scope, any child forks will be cancelled before the scope is done
              closeScope.countDown()
              false
            case t: T @unchecked =>
              inProgress.send(fork {
                try
                  val u = f(t)
                  s.release() // not in finally, as in case of an exception, no point in starting subsequent forks
                  u
                catch
                  case t: Throwable =>
                    c2.error(t)
                    closeScope.countDown()
                    throw t
              })
              true
        }
      }

      // sending fork
      fork {
        repeatWhile {
          inProgress.receive() match
            case f: Fork[U] @unchecked =>
              c2.send(f.join()).isValue
            case ChannelClosed.Done =>
              closeScope.countDown()
              c2.done()
              false
            case ChannelClosed.Error(_) =>
              throw new IllegalStateException() // inProgress is never in an error state
        }
      }

      closeScope.await()
    }

  def mapParUnordered[U](parallelism: Int)(f: T => U)(using Ox, StageCapacity): Source[U] = ??? // TODO

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
        case e: ChannelClosed.Error => throw e.toThrowable
        case t: T @unchecked =>
          v = None
          t
    Source.fromIterator(f(it))

  def merge[U >: T](other: Source[U])(using Ox, StageCapacity): Source[U] =
    val c = StageCapacity.newChannel[U]
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
    val c = StageCapacity.newChannel[(T, U)]
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

  /** Invokes the given function for each received element. Blocks until the channel is done.
    * @throws ChannelClosedException
    *   when there is an upstream error.
    */
  def foreach(f: T => Unit): Unit =
    repeatWhile {
      receive() match
        case ChannelClosed.Done     => false
        case e: ChannelClosed.Error => throw e.toThrowable
        case t: T @unchecked        => f(t); true
    }

  /** Accumulates all elements received from the channel into a list. Blocks until the channel is done.
    * @throws ChannelClosedException
    *   when there is an upstream error.
    */
  def toList: List[T] =
    val b = List.newBuilder[T]
    foreach(b += _)
    b.result()

  /** Passes each received element from this channel to the given sink. Blocks until the channel is done.
    * @throws ChannelClosedException
    *   when there is an upstream error, or when the sink is closed.
    */
  def pipeTo(sink: Sink[T]): Unit =
    repeatWhile {
      receive() match
        case ChannelClosed.Done     => sink.done(); false
        case e: ChannelClosed.Error => sink.error(e.reason); throw e.toThrowable
        case t: T @unchecked        => sink.send(t).orThrow; true
    }  

  /** Receives all elements from the channel. Blocks until the channel is done.
    * @throws ChannelClosedException
    *   when there is an upstream error.
    */
  def drain(): Unit = foreach(_ => ())

  def applied[U](f: Source[T] => U): U = f(this)
}

trait SourceCompanionOps:
  def fromIterable[T](it: Iterable[T])(using Ox, StageCapacity): Source[T] = fromIterator(it.iterator)

  def fromValues[T](ts: T*)(using Ox, StageCapacity): Source[T] = fromIterator(ts.iterator)

  def fromIterator[T](it: => Iterator[T])(using Ox, StageCapacity): Source[T] =
    val c = StageCapacity.newChannel[T]
    fork {
      val theIt = it
      try
        while theIt.hasNext do c.send(theIt.next())
        c.done()
      catch case t: Throwable => c.error(t)
    }
    c

  def fromFork[T](f: Fork[T])(using Ox, StageCapacity): Source[T] =
    val c = StageCapacity.newChannel[T]
    fork {
      try
        c.send(f.join())
        c.done()
      catch case t: Throwable => c.error(t)
    }
    c

  def iterate[T](zero: T)(f: T => T)(using Ox, StageCapacity): Source[T] =
    val c = StageCapacity.newChannel[T]
    fork {
      var t = zero
      try
        forever {
          c.send(t)
          t = f(t)
        }
      catch case t: Throwable => c.error(t)
    }
    c

  def unfold[S, T](initial: S)(f: S => Option[(T, S)])(using Ox, StageCapacity): Source[T] =
    val c = StageCapacity.newChannel[T]
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
      catch case t: Throwable => c.error(t)
    }
    c

  def tick[T](interval: FiniteDuration, element: T = ())(using Ox, StageCapacity): Source[T] =
    val c = StageCapacity.newChannel[T]
    fork {
      forever {
        c.send(element)
        Thread.sleep(interval.toMillis)
      }
    }
    c

  def repeat[T](element: T = ())(using Ox, StageCapacity): Source[T] =
    val c = StageCapacity.newChannel[T]
    fork {
      forever {
        c.send(element)
      }
    }
    c

  def timeout[T](interval: FiniteDuration, element: T = ())(using Ox, StageCapacity): Source[T] =
    val c = StageCapacity.newChannel[T]
    fork {
      Thread.sleep(interval.toMillis)
      c.send(element)
      c.done()
    }
    c

  def concat[T](sources: Seq[() => Source[T]])(using Ox, StageCapacity): Source[T] =
    val c = StageCapacity.newChannel[T]
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
      catch case t: Throwable => c.error(t)
    }
    c
