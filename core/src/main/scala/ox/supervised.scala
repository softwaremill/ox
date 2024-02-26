package ox

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{CompletableFuture, ConcurrentHashMap}

/** Starts a new scope, which allows starting forks in the given code block `f`. Forks can be started using [[fork]], [[forkUser]],
  * [[forkCancellable]] and [[forkUnsupervised]]. All forks are guaranteed to complete before this scope completes.
  *
  * The scope is ran in supervised mode, that is:
  *   - the scope ends once all user, supervised forks (started using [[forkUser]]), including the `f` main body, succeed. Forks started
  *     using [[fork]] (daemon) don't have to complete successfully for the scope to end.
  *   - the scope also ends once the first supervised fork (including the `f` main body) fails with an exception
  *   - when the scope ends, all running forks are cancelled
  *   - the scope completes (that is, this method returns) only once all forks started by `f` have completed (either successfully, or with
  *     an exception)
  *
  * Upon successful completion, returns the result of evaluating `f`. Upon failure, the exception that caused the scope to end is re-thrown
  * (regardless if the exception was thrown from the main body, or from a fork). Any other exceptions that occur when completing the scope
  * are added as suppressed.
  *
  * @see
  *   [[scoped]] Starts a scope in unsupervised mode
  */
def supervised[T](f: Ox ?=> T): T =
  val s = DefaultSupervisor()
  try
    scoped {
      val r = supervisor(s)(forkUser(f))
      s.join() // might throw if any supervised fork threw
      r.join() // if no exceptions, the main f-fork must be done by now
    }
  catch
    case e: Throwable =>
      // all forks are guaranteed to have finished: some might have ended up throwing exceptions (InterruptedException or
      // others), but only the first one is propagated below. That's why we add all the other exceptions as suppressed.
      s.addOtherExceptionsAsSuppressedTo(e)
      throw e

trait Supervisor:
  def forkStarts(): Unit
  def forkSuccess(): Unit
  def forkError(e: Throwable): Unit

  /** Wait until the count of all supervised, user forks that are running reaches 0, or until any supervised fork fails with an exception.
    *
    * The completion of this method is typically followed by ending the scope, which cancels any forks that are still running.
    *
    * Note that (daemon) forks can still start supervised user forks after this method returns.
    */
  def join(): Unit

object NoOpSupervisor extends Supervisor:
  override def forkStarts(): Unit = ()
  override def forkSuccess(): Unit = ()
  override def forkError(e: Throwable): Unit = ()
  override def join(): Unit = ()

class DefaultSupervisor() extends Supervisor:
  private val running: AtomicInteger = AtomicInteger(0)
  private val result: CompletableFuture[Unit] = new CompletableFuture()
  private val otherExceptions: java.util.Set[Throwable] = ConcurrentHashMap.newKeySet()

  override def forkStarts(): Unit = running.incrementAndGet()

  override def forkSuccess(): Unit =
    val v = running.decrementAndGet()
    if v == 0 then result.complete(())

  override def forkError(e: Throwable): Unit = if !result.completeExceptionally(e) then otherExceptions.add(e)

  override def join(): Unit = unwrapExecutionException(result.get())

  def addOtherExceptionsAsSuppressedTo(e: Throwable): Throwable =
    otherExceptions.forEach(e2 => if e != e2 then e.addSuppressed(e2))
    e

/** Change the supervisor that is being used when running `f`. Doesn't affect existing usages of the current supervisor, or forks ran
  * outside of `f`.
  */
def supervisor[T](supervisor: Supervisor)(f: Ox ?=> T)(using Ox): T =
  f(using summon[Ox].copy(supervisor = supervisor))
