package ox.channels

import ox.*

import java.util
import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionException
import scala.concurrent.Future
import scala.util.Failure
import scala.util.Success
import ox.flow.Flow

trait SourceCompanionOps:
  /** Creates an empty source, that is immediately completed as done. */
  def empty[T]: Source[T] =
    val c = Channel.rendezvous[T]
    c.doneOrClosed()
    c

  /** Creates a source that fails immediately with the given [[java.lang.Throwable]]
    *
    * @param t
    *   The [[java.lang.Throwable]] to fail with
    * @return
    *   A source that would fail immediately with the given [[java.lang.Throwable]]
    */
  def failed[T](t: Throwable): Source[T] =
    val c = Channel.rendezvous[T]
    c.errorOrClosed(t)
    c

  def fromIterable[T](it: Iterable[T])(using Ox, BufferCapacity): Source[T] = fromIterator(it.iterator)

  def fromValues[T](ts: T*)(using Ox, BufferCapacity): Source[T] = fromIterator(ts.iterator)

  def fromIterator[T](it: => Iterator[T])(using Ox, BufferCapacity): Source[T] = Flow.fromIterator(it).runToChannel()

  def fromFork[T](f: Fork[T])(using Ox, BufferCapacity): Source[T] = Flow.fromFork(f).runToChannel()

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
  def fromFuture[T](from: Future[T])(using BufferCapacity, ExecutionContext): Source[T] =
    val c = BufferCapacity.newChannel[T]
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
  def fromFutureSource[T](from: Future[Source[T]])(using Ox, BufferCapacity, ExecutionContext): Source[T] =
    val c = BufferCapacity.newChannel[T]
    val transportChannel = BufferCapacity.newChannel[Source[T]](using BufferCapacity(1))

    receiveAndSendFromFuture(from, transportChannel)

    fork {
      transportChannel.receiveOrClosed() match
        case ChannelClosed.Done           => c.doneOrClosed()
        case ChannelClosed.Error(r)       => c.errorOrClosed(r)
        case source: Source[T] @unchecked => source.pipeTo(c, propagateDone = true)
    }
    c
  end fromFutureSource

  private def receiveAndSendFromFuture[T](from: Future[T], to: Channel[T])(using ExecutionContext): Unit =
    from.onComplete {
      case Success(value)                  => to.sendOrClosed(value); to.doneOrClosed()
      case Failure(ex: ExecutionException) => to.errorOrClosed(ex.getCause)
      case Failure(ex)                     => to.errorOrClosed(ex)
    }
end SourceCompanionOps
