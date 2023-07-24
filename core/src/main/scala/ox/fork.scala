package ox

import java.util.concurrent.CompletableFuture
import scala.concurrent.ExecutionException
import scala.util.Try

/** Starts a thread, which is guaranteed to complete before the enclosing [[scoped]] block exits.
  *
  * In case an exception is thrown while evaluating `t`, it will be thrown when calling the returned [[Fork]]'s `.join()` method.
  */
def fork[T](f: => T)(using Ox): Fork[T] =
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

def forkAll[T](fs: Seq[() => T])(using Ox): Fork[Seq[T]] =
  val forks = fs.map(f => fork(f()))
  new Fork[Seq[T]]:
    override def join(): Seq[T] = forks.map(_.join())
    override def cancel(): Either[Throwable, Seq[T]] =
      val results = forks.map(_.cancel())
      if results.exists(_.isLeft)
      then Left(results.collectFirst { case Left(e) => e }.get)
      else Right(results.collect { case Right(t) => t })

/** A running fork, started using [[fork]] or [[fork]], backend by a thread. */
trait Fork[T]:
  /** Blocks until the fork completes with a result. Throws an exception, if the fork completed with an exception. */
  def join(): T

  /** Blocks until the fork completes with a result. */
  def joinEither(): Either[Throwable, T] = Try(join()).toEither

  /** Interrupts the fork, and blocks until it completes with a result. */
  def cancel(): Either[Throwable, T]
