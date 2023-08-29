package ox

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CompletableFuture

/** Starts a new scope, which allows starting forks in the given code block `f`. Forks can be started using [[fork]], [[forkDaemon]] and
  * [[forkUnsupervised]].
  *
  * The code is ran in supervised mode, that is:
  *   - the scope ends once the all non-daemon, supervised forks (including the `f` code block) complete
  *   - the scope also ends once the first supervised fork (including the `f` code block) fails with an exception
  *   - when the scope ends, all running forks are cancelled
  *   - the scope completes (that is, this method returns) only once all forks started by `f` have completed (either successfully, or with
  *     an exception)
  *
  * @see
  *   [[scoped]] Starts a scope in unsupervised mode
  */
def supervised[T](f: Ox ?=> T): T =
  scoped {
    val s = DefaultSupervisor()
    val r = supervisor(s)(fork(f))
    s.join()
    r.join() // the fork must be done by now
  }

trait Supervisor:
  def forkStarts(): Unit
  def forkSuccess(): Unit
  def forkError(e: Throwable): Unit

  /** Wait until the count of all supervised, non-daemon forks that are running reaches 0, or until any supervised fork fails with an
    * exception.
    *
    * The completion of this method is typically followed by ending the scope, which cancels any forks that are still running.
    *
    * Note that daemon forks can still start supervised non-daemon forks after this method returns.
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

  override def forkStarts(): Unit = running.incrementAndGet()

  override def forkSuccess(): Unit =
    val v = running.decrementAndGet()
    if v == 0 then result.complete(())

  override def forkError(e: Throwable): Unit = result.completeExceptionally(e)

  override def join(): Unit = unwrapExecutionException(result.get())

/** Change the supervisor that is being used when running `f`. Doesn't affect existing usages of the current supervisor, or forks ran
  * outside of `f`.
  */
def supervisor[T](supervisor: Supervisor)(f: Ox ?=> T)(using Ox): T =
  f(using summon[Ox].copy(supervisor = supervisor))
