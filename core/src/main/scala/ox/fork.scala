package ox

import java.util.concurrent.{CompletableFuture, Semaphore}
import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.ExecutionException
import scala.util.Try
import scala.util.control.NonFatal

/** Starts a fork (logical thread of execution), which is guaranteed to complete before the enclosing [[supervised]] or [[scoped]] block
  * completes.
  *
  * In case an exception is thrown while evaluating `t`, it will be thrown when calling the returned [[Fork]]'s `.join()` method.
  *
  * If ran in a supervised scope, an exception will cause the enclosing scope to end. Moreover, the scope will end only once all supervised
  * forks (such as this one) complete.
  */
def fork[T](f: => T)(using Ox): Fork[T] =
  // the separate result future is needed to wait for the result, as there's no .join on individual tasks (only whole scopes can be joined)
  val result = new CompletableFuture[T]()
  val ox = summon[Ox]
  ox.supervisor.forkStarts()
  ox.scope.fork(() => runToResult(f, result))
  newForkUsingResult(result)

/** Starts a fork (logical thread of execution), which is guaranteed to complete before the enclosing [[supervised]] or [[scoped]] block
  * exits.
  *
  * In case an exception is thrown while evaluating `t`, it will be thrown when calling the returned [[Fork]]'s `.join()` method.
  *
  * If ran in a supervised scope, an exception will cause the enclosing scope to end. However, unlike [[fork]], the enclosing scope might
  * end (cancelling this fork) before this fork completes.
  *
  * If ran in an unsupervised scope, behaves the same as [[fork]].
  */
def forkDaemon[T](f: => T)(using Ox): Fork[T] =
  val result = new CompletableFuture[T]()
  summon[Ox].scope.fork { () =>
    val supervisor = summon[Ox].supervisor
    try result.complete(f)
    catch
      case e: Throwable =>
        result.completeExceptionally(e)
        supervisor.forkError(e)
  }
  newForkUsingResult(result)

/** Starts a fork (logical thread of execution), which is guaranteed to complete before the enclosing [[supervised]] or [[scoped]] block
  * exits.
  *
  * In case an exception is thrown while evaluating `t`, it will be thrown when calling the returned [[Fork]]'s `.join()` method.
  *
  * Success or failure isn't signalled to the supervisor. If ran in an unsupervised scope, behaves the same as [[fork]].
  */
def forkUnsupervised[T](f: => T)(using Ox): Fork[T] =
  val result = new CompletableFuture[T]()
  summon[Ox].scope.fork { () =>
    try result.complete(f)
    catch case e: Throwable => result.completeExceptionally(e)
  }
  newForkUsingResult(result)

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
  val ox = summon[Ox]
  ox.supervisor.forkStarts()
  ox.scope.fork { () =>
    scoped {
      supervisor(ox.supervisor) {
        val nestedOx = summon[Ox]
        nestedOx.scope.fork { () =>
          // "else" means that the fork is already cancelled, so doing nothing in that case
          if !started.getAndSet(true) then runToResult(f, result)
        }

        done.acquire()
      }
    }
  }
  new CancellableFork[T]:
    override def join(): T = unwrapExecutionException(result.get())

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

private def runToResult[T](f: => T, result: CompletableFuture[T])(using Ox): Unit =
  val supervisor = summon[Ox].supervisor
  try
    result.complete(f)
    supervisor.forkSuccess()
  catch
    case e: Throwable =>
      result.completeExceptionally(e)
      supervisor.forkError(e)

private def newForkUsingResult[T](result: CompletableFuture[T]): Fork[T] = new Fork[T]:
  override def join(): T = unwrapExecutionException(result.get())

private[ox] def unwrapExecutionException[T](f: => T): T =
  try f
  catch
    case e: ExecutionException => throw e.getCause
    case e: Throwable          => throw e

//

/** A fork started using [[fork]], [[forkDaemon]] or [[forkUnsupervised]], backed by a (virtual) thread. */
trait Fork[T]:
  /** Blocks until the fork completes with a result. Throws an exception, if the fork completed with an exception. */
  def join(): T

  /** Blocks until the fork completes with a result. */
  def joinEither(): Either[Throwable, T] = Try(join()).toEither

/** A fork started using [[forkCancellable]], backed by a (virtual) thread. */
trait CancellableFork[T] extends Fork[T]:
  /** Interrupts the fork, and blocks until it completes with a result. */
  def cancel(): Either[Throwable, T]

  /** Interrupts the fork, and returns immediately, without waiting for the fork to complete. Note that the enclosing scope will only
    * complete once all forks have completed.
    */
  def cancelNow(): Unit
