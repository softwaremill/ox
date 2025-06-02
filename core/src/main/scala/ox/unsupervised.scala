package ox

import ox.internal.currentLocals
import ox.internal.currentScope

/** Starts a new concurrency scope, which allows starting forks in the given code block `f`. Forks can be started using
  * [[forkUnsupervised]], and [[forkCancellable]]. All forks are guaranteed to complete before this scope completes.
  *
  * It is advisable to use [[supervised]] scopes if possible, as they minimize the chances of an error to go unnoticed.
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
def unsupervised[T](f: OxUnsupervised ?=> T): T = unsupervised(currentLocals, f)

private[ox] def unsupervised[T](locals: ForkLocalMap, f: OxUnsupervised ?=> T): T =
  scopedWithCapability(OxError(NoOpSupervisor, NoErrorMode, Option(currentScope.get()), locals))(f)

private[ox] def scopedWithCapability[T](capability: Ox)(f: Ox ?=> T): T =
  def throwWithSuppressed(es: List[Throwable]): Nothing =
    val e = es.head
    es.tail.foreach(e.addSuppressed)
    throw e

  val herd = capability.herd
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

  val previousScope = currentScope.get()
  currentScope.set(capability)
  try
    val t =
      try f(using capability)
      finally herd.interruptAllAndJoinUntilCompleted()

    // running the finalizers only once we are sure that all child threads have been terminated, so that no new
    // finalizers are added, and none are lost
    runFinalizers(Right(t))
  catch case e: Throwable => runFinalizers(Left(e))
  finally currentScope.set(previousScope)
  end try
end scopedWithCapability
