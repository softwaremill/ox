package ox

import java.util.concurrent.{CompletableFuture, Semaphore}
import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.ExecutionException
import scala.util.Try
import scala.util.control.NonFatal

/** Starts a thread, which is guaranteed to complete before the enclosing [[scoped]] block exits.
  *
  * In case an exception is thrown while evaluating `t`, it will be thrown when calling the returned [[Fork]]'s `.join()` method.
  */
def fork[T](f: => T)(using Ox): Fork[T] =
  // the separate result future is needed to wait for the result, as there's no .join on individual tasks (only whole scopes can be joined)
  val result = new CompletableFuture[T]()
  summon[Ox].scope.fork { () =>
    try result.complete(f)
    catch case e: Throwable => result.completeExceptionally(e)
  }
  new Fork[T]:
    override def join(): T = try result.get()
    catch
      case e: ExecutionException => throw e.getCause
      case e: Throwable          => throw e

def forkAll[T](fs: Seq[() => T])(using Ox): Fork[Seq[T]] =
  val forks = fs.map(f => fork(f()))
  new Fork[Seq[T]]:
    override def join(): Seq[T] = forks.map(_.join())

/** A cancellable fork is created by starting a nested scope in a fork, and then starting a fork there. Hence, it is more expensive than
  * `fork`, as two virtual threads are started.
  *
  * Otherwise, works same as [[fork]].
  */
def forkCancellable[T](f: => T)(using Ox): CancellableFork[T] =
  val result = new CompletableFuture[T]()
  // forks can be never run, if they are cancelled immediately - we need to detect this, not to await on result.get()
  val started = new AtomicBoolean(false)
  // interrupt signal
  val done = new Semaphore(0)
  summon[Ox].scope.fork { () =>
    scoped {
      val nestedOx = summon[Ox]
      nestedOx.scope.fork { () =>
        // "else" means that the fork is already cancelled, so doing nothing in that case
        if !started.getAndSet(true) then
          try result.complete(f)
          catch case e: Throwable => result.completeExceptionally(e)
      }

      done.acquire()
    }
  }
  new CancellableFork[T]:
    override def join(): T = try result.get()
    catch
      case e: ExecutionException => throw e.getCause
      case e: Throwable          => throw e

    override def cancel(): Either[Throwable, T] =
      cancelNow()
      try Right(result.get())
      catch
        case e: ExecutionException => Left(e.getCause)
        case e: Throwable          => Left(e)

    override def cancelNow(): Unit =
      // will cause the scope to end, interrupting the task if it hasn't yet finished (or potentially never starting it)
      done.release()
      if !started.getAndSet(true)
      then result.completeExceptionally(new InterruptedException("fork was cancelled before it started"))

/** A running fork, started using [[fork]] or [[fork]], backend by a thread. */
trait Fork[T]:
  /** Blocks until the fork completes with a result. Throws an exception, if the fork completed with an exception. */
  def join(): T

  /** Blocks until the fork completes with a result. */
  def joinEither(): Either[Throwable, T] = Try(join()).toEither

trait CancellableFork[T] extends Fork[T]:
  /** Interrupts the fork, and blocks until it completes with a result. */
  def cancel(): Either[Throwable, T]

  /** Interrupts the fork, and returns immediately, without waiting for the fork complete. Note that the enclosing scope will only complete
    * once all forks have completed.
    */
  def cancelNow(): Unit
