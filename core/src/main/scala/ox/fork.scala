package ox

import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.ExecutionException
import scala.util.Try

/** Starts a thread, which is guaranteed to complete before the enclosing [[scoped]] block exits.
  *
  * In case an exception is thrown while evaluating `t`, it will be thrown when calling the returned [[Fork]]'s `.join()` method.
  */
def fork[T](f: => T)(using Ox): Fork[T] =
  // the separate result future is needed to wait for a result when cancelled (forkFuture is immediately completed upon cancellation)
  val result = new CompletableFuture[T]()
  // forks can be never run, if they are cancelled immediately - we need to detect this, not to await on result.get()
  val started = new AtomicBoolean(false)
  val forkFuture = summon[Ox].scope.fork { () =>
    // the "else" should never happen, but it would mean that the fork is already cancelled, so doing nothing in that case
    if !started.getAndSet(true) then
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
      if started.getAndSet(true)
      then
        try Right(result.get())
        catch
          case e: ExecutionException => Left(e.getCause)
          case e: Throwable          => Left(e)
      else Left(new InterruptedException("fork was cancelled before it started"))
    override def cancelNow(): Unit = forkFuture.cancel(false)

def forkAll[T](fs: Seq[() => T])(using Ox): Fork[Seq[T]] =
  val forks = fs.map(f => fork(f()))
  new Fork[Seq[T]]:
    override def join(): Seq[T] = forks.map(_.join())
    override def cancel(): Either[Throwable, Seq[T]] =
      val results = forks.map(_.cancel())
      if results.exists(_.isLeft)
      then Left(results.collectFirst { case Left(e) => e }.get)
      else Right(results.collect { case Right(t) => t })
    override def cancelNow(): Unit = forks.foreach(_.cancelNow())

/** A running fork, started using [[fork]] or [[fork]], backend by a thread. */
trait Fork[T]:
  /** Blocks until the fork completes with a result. Throws an exception, if the fork completed with an exception. */
  def join(): T

  /** Blocks until the fork completes with a result. */
  def joinEither(): Either[Throwable, T] = Try(join()).toEither

  /** Interrupts the fork, and blocks until it completes with a result. */
  def cancel(): Either[Throwable, T]

  /** Interrupts the fork, and returns immediately, without waiting for the fork complete. Note that the enclosing scope will only complete
    * once all forks have completed.
    */
  def cancelNow(): Unit
