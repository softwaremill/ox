package ox

import java.util.concurrent.{CompletableFuture, Semaphore}
import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.ExecutionException
import scala.util.control.NonFatal

/** Starts a fork (logical thread of execution), which is guaranteed to complete before the enclosing [[supervised]] or [[scoped]] block
  * completes.
  *
  * If ran in a [[supervised]] scope:
  *
  *   - the fork behaves as a daemon thread
  *   - an exception thrown while evaluating `t` will cause the enclosing scope to end (cancelling all other running forks)
  *   - if the main body of the scope completes successfully, and all other user forks complete successfully, the scope will end, cancelling
  *     all running forks (including this one, if it's still running). That is, successful completion of this fork isn't required to end the
  *     scope.
  *
  * For alternate behaviors, see [[forkUser]], [[forkCancellable]] and [[forkUnsupervised]].
  *
  * If ran in an unsupervised scope ([[scoped]]):
  *
  *   - in case an exception is thrown while evaluating `t`, it will be thrown when calling the returned [[Fork]]'s `.join()` method.
  *   - if the main body of the scope completes successfully, while this fork is still running, the fork will be cancelled
  */
def fork[T](f: => T)(using Ox): Fork[T] =
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
  * completes.
  *
  * If ran in a [[supervised]] scope:
  *
  *   - the fork behaves as a user-level thread
  *   - an exception thrown while evaluating `t` will cause the enclosing scope to end (cancelling all other running forks)
  *   - the scope won't end until the main body of the scope, and all other user forks (including this one) complete successfully. That is,
  *     successful completion of this fork is required to end the scope.
  *
  * For alternate behaviors, see [[fork]], [[forkCancellable]] and [[forkUnsupervised]].
  *
  * If ran in an unsupervised scope ([[scoped]]):
  *
  *   - in case an exception is thrown while evaluating `t`, it will be thrown when calling the returned [[Fork]]'s `.join()` method.
  *   - if the main body of the scope completes successfully, while this fork is still running, the fork will be cancelled
  */
def forkUser[T](f: => T)(using Ox): Fork[T] =
  // the separate result future is needed to wait for the result, as there's no .join on individual tasks (only whole scopes can be joined)
  val result = new CompletableFuture[T]()
  val ox = summon[Ox]
  ox.supervisor.forkStarts()
  ox.scope.fork { () =>
    val supervisor = summon[Ox].supervisor
    try
      result.complete(f)
      supervisor.forkSuccess()
    catch
      case e: Throwable =>
        // we notify the supervisor first, so that if this is the first failing fork in the scope, the supervisor will
        // get first notified of the exception by the "original" (this) fork
        supervisor.forkError(e)
        result.completeExceptionally(e)
  }
  newForkUsingResult(result)

/** Starts a fork (logical thread of execution), which is guaranteed to complete before the enclosing [[supervised]] or [[scoped]] block
  * completes.
  *
  * In case an exception is thrown while evaluating `t`, it will be thrown when calling the returned [[Fork]]'s `.join()` method.
  *
  * Success or failure isn't signalled to the supervisor, and doesn't influence the scope's lifecycle.
  *
  * For alternate behaviors, see [[fork]], [[forkUser]] and [[forkCancellable]].
  */
def forkUnsupervised[T](f: => T)(using Ox): Fork[T] =
  val result = new CompletableFuture[T]()
  summon[Ox].scope.fork { () =>
    try result.complete(f)
    catch case e: Throwable => result.completeExceptionally(e)
  }
  newForkUsingResult(result)

/** For each thunk in the given sequence, starts a fork using [[fork]]. All forks are guaranteed to complete before the enclosing
  * [[supervised]] or [[scoped]] block completes.
  *
  * If ran in a [[supervised]] scope, all forks behave as daemon threads (see [[fork]] for details).
  */
def forkAll[T](fs: Seq[() => T])(using Ox): Fork[Seq[T]] =
  val forks = fs.map(f => fork(f()))
  new Fork[Seq[T]]:
    override def join(): Seq[T] = forks.map(_.join())

/** Starts a fork (logical thread of execution), which is guaranteed to complete before the enclosing [[supervised]] or [[scoped]] block
  * completes, and which can be cancelled on-dmeand.
  *
  * In case an exception is thrown while evaluating `t`, it will be thrown when calling the returned [[Fork]]'s `.join()` method.
  *
  * The fork is unsupervised (similarly to [[forkUnsupervised]]), hence success or failure isn't signalled to the supervisor, and doesn't
  * influence the scope's lifecycle.
  *
  * For alternate behaviors, see [[fork]], [[forkUser]] and [[forkUnsupervised]].
  *
  * Implementation note: a cancellable fork is created by starting a nested scope in a fork, and then starting a fork there. Hence, it is
  * more expensive than [[fork]], as two virtual threads are started.
  */
def forkCancellable[T](f: => T)(using Ox): CancellableFork[T] =
  val result = new CompletableFuture[T]()
  // forks can be never run, if they are cancelled immediately - we need to detect this, not to await on result.get()
  val started = new AtomicBoolean(false)
  // interrupt signal
  val done = new Semaphore(0)
  val ox = summon[Ox]
  ox.scope.fork { () =>
    scoped {
      supervisor(ox.supervisor) {
        val nestedOx = summon[Ox]
        nestedOx.scope.fork { () =>
          // "else" means that the fork is already cancelled, so doing nothing in that case
          if !started.getAndSet(true) then
            try result.complete(f)
            catch case e: Throwable => result.completeExceptionally(e)

          done.release() // the nested scope can now finish
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
        // we don't want to catch fatal exceptions (excluding IE, which is fatal for the cancelled thread only)
        case e: ExecutionException if e.getCause.isInstanceOf[InterruptedException] => Left(e.getCause)
        case e: ExecutionException if NonFatal.unapply(e.getCause).isDefined        => Left(e.getCause)
        case e: InterruptedException                                                => Left(e)
        case NonFatal(e)                                                            => Left(e)

    override def cancelNow(): Unit =
      // will cause the scope to end, interrupting the task if it hasn't yet finished (or potentially never starting it)
      done.release()
      if !started.getAndSet(true)
      then result.completeExceptionally(new InterruptedException("fork was cancelled before it started"))

private def newForkUsingResult[T](result: CompletableFuture[T]): Fork[T] = new Fork[T]:
  override def join(): T = unwrapExecutionException(result.get())

private[ox] def unwrapExecutionException[T](f: => T): T =
  try f
  catch
    case e: ExecutionException => throw e.getCause
    case e: Throwable          => throw e

//

/** A fork started using [[fork]], [[forkUser]], [[forkCancellable]] or [[forkUnsupervised]], backed by a (virtual) thread. */
trait Fork[T]:
  /** Blocks until the fork completes with a result. Throws an exception, if the fork completed with an exception. */
  def join(): T

  /** Blocks until the fork completes with a result. */
  def joinEither(): Either[Throwable, T] =
    try Right(join())
    catch
      // normally IE is fatal, but here it was meant to cancel the fork, not the joining parent, hence we catch it
      case e: InterruptedException => Left(e)
      case NonFatal(e)             => Left(e)

/** A fork started using [[forkCancellable]], backed by a (virtual) thread. */
trait CancellableFork[T] extends Fork[T]:
  /** Interrupts the fork, and blocks until it completes with a result. */
  def cancel(): Either[Throwable, T]

  /** Interrupts the fork, and returns immediately, without waiting for the fork to complete. Note that the enclosing scope will only
    * complete once all forks have completed.
    */
  def cancelNow(): Unit
