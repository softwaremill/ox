package ox.channels

import ox.*
import ox.channels.ChannelClosedUnion.isValue

import java.util
import scala.collection.mutable
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, ExecutionException, Future}
import scala.util.{Failure, Success}

trait SourceCompanionOps:
  def fromIterable[T](it: Iterable[T])(using Ox, StageCapacity): Source[T] = fromIterator(it.iterator)

  def fromValues[T](ts: T*)(using Ox, StageCapacity): Source[T] = fromIterator(ts.iterator)

  def fromIterator[T](it: => Iterator[T])(using Ox, StageCapacity): Source[T] =
    val c = StageCapacity.newChannel[T]
    fork {
      val theIt = it
      try
        while theIt.hasNext do c.sendSafe(theIt.next())
        c.doneSafe()
      catch case t: Throwable => c.errorSafe(t)
    }
    c

  def fromFork[T](f: Fork[T])(using Ox, StageCapacity): Source[T] =
    val c = StageCapacity.newChannel[T]
    fork {
      try
        c.sendSafe(f.join())
        c.doneSafe()
      catch case t: Throwable => c.errorSafe(t)
    }
    c

  def iterate[T](zero: T)(f: T => T)(using Ox, StageCapacity): Source[T] =
    val c = StageCapacity.newChannel[T]
    fork {
      var t = zero
      try
        forever {
          c.sendSafe(t)
          t = f(t)
        }
      catch case t: Throwable => c.errorSafe(t)
    }
    c

  /** A range of number, from `from`, to `to` (inclusive), stepped by `step`. */
  def range(from: Int, to: Int, step: Int)(using Ox, StageCapacity): Source[Int] =
    val c = StageCapacity.newChannel[Int]
    fork {
      var t = from
      try
        repeatWhile {
          c.sendSafe(t)
          t = t + step
          t <= to
        }
        c.doneSafe()
      catch case t: Throwable => c.errorSafe(t)
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
              c.sendSafe(value)
              s = next
              true
            case None =>
              c.doneSafe()
              false
        }
      catch case t: Throwable => c.errorSafe(t)
    }
    c

  /** Creates a rendezvous channel (without a buffer, regardless of the [[StageCapacity]] in scope), to which the given value is sent
    * repeatedly, at least [[interval]] apart between each two elements. The first value is sent immediately.
    *
    * The interval is measured between the subsequent invocations of the `send(value)` method. Hence, if there's a slow consumer, the next
    * tick can be sent right after the previous one is received (if it was received later than the inter-tick interval duration). However,
    * ticks don't accumulate, e.g. when the consumer is so slow that multiple intervals pass between `send` invocations.
    *
    * Must be run within a scope, since a child fork is created which sends the ticks, and waits until the next tick can be sent.
    *
    * @param interval
    *   The temporal spacing between subsequent ticks.
    * @param value
    *   The value to send to the channel on every tick.
    * @return
    *   A source to which the tick values are sent.
    * @example
    *   {{{
    *   scala>
    *   import ox.*
    *   import ox.channels.Source
    *   import scala.concurrent.duration.DurationInt
    *
    *   supervised {
    *     val s1 = Source.tick(100.millis)
    *     s1.receive()
    *     s2.receive() // this will complete at least 100 milliseconds later
    *   }
    *   }}}
    */
  def tick[T](interval: FiniteDuration, value: T = ())(using Ox): Source[T] =
    val c = Channel.rendezvous[T]
    fork {
      forever {
        val start = System.nanoTime()
        c.sendSafe(value)
        val end = System.nanoTime()
        val sleep = interval.toNanos - (end - start)
        if sleep > 0 then Thread.sleep(sleep / 1_000_000, (sleep % 1_000_000).toInt)
      }
    }
    c

  /** Creates a channel, to which the given `element` is sent repeatedly.
    *
    * @param element
    *   The element to send
    * @return
    *   A source to which the given element is sent repeatedly.
    */
  def repeat[T](element: T = ())(using Ox, StageCapacity): Source[T] = repeatEval(element)

  /** Creates a channel, to which the result of evaluating `f` is sent repeatedly. As the parameter is passed by-name, the evaluation is
    * deferred until the element is sent, and happens multiple times.
    *
    * @param f
    *   The code block, computing the element to send.
    * @return
    *   A source to which the result of evaluating `f` is sent repeatedly.
    */
  def repeatEval[T](f: => T)(using Ox, StageCapacity): Source[T] =
    val c = StageCapacity.newChannel[T]
    fork {
      try
        forever {
          c.sendSafe(f)
        }
      catch case t: Throwable => c.errorSafe(t)
    }
    c

  /** Creates a channel, to which the value contained in the result of evaluating `f` is sent repeatedly. When the evaluation of `f` returns
    * a `None`, the channel is completed as "done", and no more values are evaluated or sent.
    *
    * As the `f` parameter is passed by-name, the evaluation is deferred until the element is sent, and happens multiple times.
    *
    * @param f
    *   The code block, computing the optional element to send.
    * @return
    *   A source to which the value contained in the result of evaluating `f` is sent repeatedly.
    */
  def repeatEvalWhileDefined[T](f: => Option[T])(using Ox, StageCapacity): Source[T] =
    val c = StageCapacity.newChannel[T]
    fork {
      try
        repeatWhile {
          f match
            case Some(value) => c.sendSafe(value); true
            case None        => c.doneSafe(); false
        }
      catch case t: Throwable => c.errorSafe(t)
    }
    c

  def timeout[T](interval: FiniteDuration, element: T = ())(using Ox, StageCapacity): Source[T] =
    val c = StageCapacity.newChannel[T]
    fork {
      Thread.sleep(interval.toMillis)
      c.sendSafe(element)
      c.doneSafe()
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
              c.doneSafe()
              continue = false
            case Some(source) =>
              source.receiveSafe() match
                case ChannelClosed.Done =>
                  currentSource = None
                case ChannelClosed.Error(r) =>
                  c.errorSafe(r)
                  continue = false
                case t: T @unchecked =>
                  c.sendSafe(t)
      catch case t: Throwable => c.errorSafe(t)
    }
    c

  def empty[T]: Source[T] =
    val c = Channel.rendezvous[T]
    c.doneSafe()
    c

  /** Sends a given number of elements (determined byc `segmentSize`) from each source in `sources` to the returned channel and repeats. The
    * order of elements in all sources is preserved.
    *
    * If any of the sources is done before the others, the behavior depends on the `eagerCancel` flag. When set to `true`, the returned
    * channel is completed immediately, otherwise the interleaving continues with the remaining non-completed sources. Once all but one
    * sources are complete, the elements of the remaining non-complete source are sent to the returned channel.
    *
    * Must be run within a scope, since a child fork is created which receives from the subsequent sources and sends to the resulting
    * channel.
    *
    * @param sources
    *   The sources whose elements will be interleaved.
    * @param segmentSize
    *   The number of elements sent from each source before switching to the next one. Default is 1.
    * @param eagerComplete
    *   If `true`, the returned channel is completed as soon as any of the sources completes. If 'false`, the interleaving continues with
    *   the remaining non-completed sources.
    * @return
    *   A source to which the interleaved elements from both sources would be sent.
    * @example
    *   {{{
    *   scala>
    *   import ox.*
    *   import ox.channels.Source
    *
    *   supervised {
    *     val s1 = Source.fromValues(1, 2, 3, 4, 5, 6, 7, 8)
    *     val s2 = Source.fromValues(10, 20, 30)
    *     val s3 = Source.fromValues(100, 200, 300, 400, 500)
    *     Source.interleaveAll(List(s1, s2, s3), segmentSize = 2, eagerComplete = true).toList
    *   }
    *
    *   scala> val res0: List[Int] = List(1, 2, 10, 20, 100, 200, 3, 4, 30)
    *   }}}
    */
  def interleaveAll[T](sources: Seq[Source[T]], segmentSize: Int = 1, eagerComplete: Boolean = false)(using
      Ox,
      StageCapacity
  ): Source[T] =
    sources match
      case Nil           => Source.empty
      case single :: Nil => single
      case _ =>
        val c = StageCapacity.newChannel[T]

        fork {
          val availableSources = mutable.ArrayBuffer.from(sources)
          var currentSourceIndex = 0
          var elementsRead = 0

          def completeCurrentSource(): Unit =
            availableSources.remove(currentSourceIndex)
            currentSourceIndex = if (currentSourceIndex == 0) availableSources.size - 1 else currentSourceIndex - 1

          def switchToNextSource(): Unit =
            currentSourceIndex = (currentSourceIndex + 1) % availableSources.size
            elementsRead = 0

          repeatWhile {
            availableSources(currentSourceIndex).receiveSafe() match
              case ChannelClosed.Done =>
                completeCurrentSource()

                if (eagerComplete || availableSources.isEmpty)
                  c.doneSafe()
                  false
                else
                  switchToNextSource()
                  true
              case ChannelClosed.Error(r) =>
                c.errorSafe(r)
                false
              case value: T @unchecked =>
                elementsRead += 1
                // after reaching segmentSize, only switch to next source if there's any other available
                if (elementsRead == segmentSize && availableSources.size > 1) switchToNextSource()
                c.sendSafe(value).isValue
          }
        }
        c

  /** Creates a source that emits a single value when `from` completes or fails otherwise. The `from` completion is performed on the
    * provided [[scala.concurrent.ExecutionContext]]. Note that when `from` fails with [[scala.concurrent.ExecutionException]] then its
    * cause is returned as source failure.
    *
    * @param from
    *   A [[scala.concurrent.Future]] that returns value upon completion.
    * @return
    *   A source that will emit value upon a `from` [[scala.concurrent.Future]] completion.
    * @example
    *   {{{
    *   import ox.*
    *   import ox.channels.Source
    *
    *   import scala.concurrent.ExecutionContext.Implicits.global
    *   import scala.concurrent.Future
    *
    *   supervised {
    *     Source
    *       .future(Future.failed(new RuntimeException("future failed")))
    *       .receive()                               // ChannelClosed.Error(java.lang.RuntimeException: future failed)
    *     Source.future(Future.successful(1)).toList // List(1)
    *   }
    *   }}}
    */
  def future[T](from: Future[T])(using StageCapacity, ExecutionContext): Source[T] =
    val c = StageCapacity.newChannel[T]
    receiveAndSendFromFuture(from, c)
    c

  /** Creates a source that emits elements from future source when `from` completes or fails otherwise. The `from` completion is performed
    * on the provided [[scala.concurrent.ExecutionContext]] whereas elements are emitted through Ox. Note that when `from` fails with
    * [[scala.concurrent.ExecutionException]] then its cause is returned as source failure.
    *
    * @param from
    *   A [[scala.concurrent.Future]] that returns source upon completion.
    * @return
    *   A source that will emit values upon a `from` [[scala.concurrent.Future]] completion.
    * @example
    *   {{{
    *   import ox.*
    *   import ox.channels.Source
    *
    *   import scala.concurrent.ExecutionContext.Implicits.global
    *   import scala.concurrent.Future
    *
    *   supervised {
    *     Source
    *       .futureSource(Future.failed(new RuntimeException("future failed")))
    *       .receive()                                                           // ChannelClosed.Error(java.lang.RuntimeException: future failed)
    *     Source.futureSource(Future.successful(Source.fromValues(1, 2))).toList // List(1, 2)
    *   }
    *   }}}
    */
  def futureSource[T](from: Future[Source[T]])(using Ox, StageCapacity, ExecutionContext): Source[T] =
    val c = StageCapacity.newChannel[T]
    val transportChannel = StageCapacity.newChannel[Source[T]](using StageCapacity(1))

    receiveAndSendFromFuture(from, transportChannel)

    fork {
      transportChannel.receiveSafe() match
        case ChannelClosed.Done           => c.doneSafe()
        case ChannelClosed.Error(r)       => c.errorSafe(r)
        case source: Source[T] @unchecked => source.pipeTo(c)
    }
    c

  private def receiveAndSendFromFuture[T](from: Future[T], to: Channel[T])(using ExecutionContext): Unit = {
    from.onComplete {
      case Success(value)                  => to.sendSafe(value); to.doneSafe()
      case Failure(ex: ExecutionException) => to.errorSafe(ex.getCause)
      case Failure(ex)                     => to.errorSafe(ex)
    }
  }

  /** Creates a source that fails immediately with the given [[java.lang.Throwable]]
    *
    * @param t
    *   The [[java.lang.Throwable]] to fail with
    * @return
    *   A source that would fail immediately with the given [[java.lang.Throwable]]
    */
  def failed[T](t: Throwable): Source[T] =
    val c = Channel.rendezvous[T]
    c.errorSafe(t)
    c
