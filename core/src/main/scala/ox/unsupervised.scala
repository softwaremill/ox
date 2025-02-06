package ox

import java.util.concurrent.StructuredTaskScope

private class DoNothingScope[T] extends StructuredTaskScope[T](null, Thread.ofVirtual().factory()) {}

/** Starts a new concurrency scope, which allows starting forks in the given code block `f`. Forks can be started using
  * [[forkUnsupervised]], and [[forkCancellable]]. All forks are guaranteed to complete before this scope completes.
  *
  * It is advisable to use [[supervised]] scopes if possible, as they minimise the chances of an error to go unnoticed.
  *
  * The scope is ran in unsupervised mode, that is:
  *   - the scope ends once the `f` body completes; this causes any running forks started within `f` to be cancelled
  *   - the scope completes (that is, this method returns) only once all forks started by `f` have completed (either successfully, or with
  *     an exception)
  *   - fork failures aren't handled in any special way, but can be inspected using [[Fork.join()]]
  *
  * Upon successful completion, returns the result of evaluating `f`. Upon failure, that is an exception thrown by `f`, it is re-thrown.
  *
  * @see
  *   [[supervised]] Starts a scope in supervised mode
  */
def unsupervised[T](f: OxUnsupervised ?=> T): T =
  scopedWithCapability(OxError(NoOpSupervisor, NoErrorMode))(f)

private[ox] def scopedWithCapability[T](capability: Ox)(f: Ox ?=> T): T =
  def throwWithSuppressed(es: List[Throwable]): Nothing =
    val e = es.head
    es.tail.foreach(e.addSuppressed)
    throw e

  val scope = capability.scope
  val finalizers = capability.finalizers
  def runFinalizers(result: Either[Throwable, T]): T =
    val fs = finalizers.get
    if fs.isEmpty then result.fold(throw _, identity)
    else
      val es = uninterruptible {
        fs.flatMap { f =>
          try
            f(); None
          catch case e: Throwable => Some(e)
        }
      }

      result match
        case Left(e)                => throwWithSuppressed(e :: es)
        case Right(t) if es.isEmpty => t
        case _                      => throwWithSuppressed(es)
    end if
  end runFinalizers

  // if this is inlined manually (in the program's text), it gets incorrectly indented
  inline def runFAndJoinScope =
    try f(using capability)
    finally
      scope.shutdown()
      scope.join().discard

  try
    val t =
      try runFAndJoinScope
      finally scope.close() // join might have been interrupted, hence the finally

    // running the finalizers only once we are sure that all child threads have been terminated, so that no new
    // finalizers are added, and none are lost
    runFinalizers(Right(t))
  catch case e: Throwable => runFinalizers(Left(e))
  end try
end scopedWithCapability
