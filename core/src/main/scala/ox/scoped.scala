package ox

import java.util.concurrent.StructuredTaskScope
import java.util.concurrent.atomic.AtomicReference

private class DoNothingScope[T] extends StructuredTaskScope[T](null, Thread.ofVirtual().factory()) {}

/** Starts a new scope, which allows starting forks in the given code block `f`. Forks can be started using [[fork]].
  *
  * The code is ran in unsupervised mode, that is:
  *   - the scope ends once the `f` code block completes; this causes any running forks started within `f` to be cancelled
  *   - the scope completes (that is, this method returns) only once all forks started by `f` have completed (either successfully, or with
  *     an exception)
  *   - fork failures aren't handled in any special way, but can be inspected using [[Fork.join()]]
  *
  * Forks created using [[forkDaemon]] and [[forkUnsupervised]] will behave exactly the same as forks created using [[fork]].
  *
  * @see
  *   [[supervised]] Starts a scope in supervised mode
  */
def scoped[T](f: Ox ?=> T): T =
  def throwWithSuppressed(es: List[Throwable]): Nothing =
    val e = es.head
    es.tail.foreach(e.addSuppressed)
    throw e

  val finalizers = new AtomicReference(List.empty[() => Unit])
  def runFinalizers(result: Either[Throwable, T]): T =
    val fs = finalizers.get
    if fs.isEmpty then result.fold(throw _, identity)
    else
      val es = uninterruptible {
        fs.flatMap { f =>
          try { f(); None }
          catch case e: Throwable => Some(e)
        }
      }

      result match
        case Left(e)                => throwWithSuppressed(e :: es)
        case Right(t) if es.isEmpty => t
        case _                      => throwWithSuppressed(es)

  val scope = new DoNothingScope[Any]()
  try
    val t =
      try
        try f(using Ox(scope, finalizers, NoOpSupervisor))
        finally
          scope.shutdown()
          scope.join()
      // join might have been interrupted
      finally scope.close()

    // running the finalizers only once we are sure that all child threads have been terminated, so that no new
    // finalizers are added, and none are lost
    runFinalizers(Right(t))
  catch case e: Throwable => runFinalizers(Left(e))
