package ox

import java.util.concurrent.CompletableFuture
import scala.concurrent.ExecutionException
import scala.util.Try

/** Starts a thread, which is guaranteed to complete before the enclosing [[scoped]] block exits.
  *
  * Exceptions are propagated. In case an exception is thrown while evaluating `t`, the enclosing scope's main thread is interrupted and the
  * exception is re-thrown there.
  */
def fork[T](f: => T)(using Ox): Fork[T] = forkHold {
  try f
  catch
    // not propagating interrupts, as these are not failures coming from evaluating `f` itself
    case e: InterruptedException => throw e
    case e: Throwable =>
      val old = summon[Ox].forkFailureToPropagate.getAndSet(e) // TODO: only the last failure is propagated
      if (old == null) summon[Ox].scopeThread.interrupt()
      throw e
}

/** Starts a thread, which is guaranteed to complete before the enclosing [[scoped]] block exits.
  *
  * Exceptions are held. In case an exception is thrown while evaluating `t`, it will be thrown when calling the returned [[Fork]]'s
  * `.join()` method. The exception is **not** propagated to the enclosing scope's main thread, like in the case of [[fork]].
  */
def forkHold[T](f: => T)(using Ox): Fork[T] =
  val result = new CompletableFuture[T]()
  val forkFuture = summon[Ox].scope.fork { () =>
    try result.complete(f)
    catch case e: Throwable => result.completeExceptionally(e)
  }
  new Fork[T]:
    override def join(): T = try result.get()
    catch
      case e: ExecutionException => throw e.getCause
      case e: Throwable          => throw e
    override def cancel(): Either[Throwable, T] =
      forkFuture.cancel(true)
      try Right(result.get())
      catch
        case e: ExecutionException => Left(e.getCause)
        case e: Throwable          => Left(e)

def forkAllHold[T](fs: Seq[() => T])(using Ox): Fork[Seq[T]] =
  val forks = fs.map(f => forkHold(f()))
  new Fork[Seq[T]]:
    override def join(): Seq[T] = forks.map(_.join())
    override def cancel(): Either[Throwable, Seq[T]] =
      val results = forks.map(_.cancel())
      if results.exists(_.isLeft)
      then Left(results.collectFirst { case Left(e) => e }.get)
      else Right(results.collect { case Right(t) => t })

/** A running fork, started using [[fork]] or [[forkHold]], backend by a thread. */
trait Fork[T]:
  /** Blocks until the fork completes with a result. Throws an exception, if the fork completed with an exception. */
  def join(): T

  /** Blocks until the fork completes with a result. */
  def joinEither(): Either[Throwable, T] = Try(join()).toEither

  /** Interrupts the fork, and blocks until it completes with a result. */
  def cancel(): Either[Throwable, T]
