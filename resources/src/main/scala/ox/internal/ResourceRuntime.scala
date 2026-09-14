package ox.internal

import ox.ResourceScope

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException

/** Resource finalization runtime shared by dedicated resource scopes and concurrency scopes. */
private[ox] object ResourceRuntime:
  private[ox] final case class Awaited[T](completed: Either[Throwable, T], interruption: InterruptedException)

  /** The dynamically active concurrency scope. Core uses this same thread-local for its scope validation and cleanup backend. */
  private[ox] val currentConcurrencyScope = new ThreadLocal[ResourceScope.Concurrent]()

  /** Runs `f` to completion even if the waiting thread is interrupted. */
  private[ox] def uninterruptible[T](f: => T): T =
    val scope = currentConcurrencyScope.get()
    if scope == null then onVirtualThread(f) else scope.runUninterruptibly(f)

  /** Freezes and runs a scope's finalizers in LIFO order, then combines their failures with the scope result. */
  private[ox] def runFinalizers[T](scope: ResourceScope, result: Either[Throwable, T]): T =
    val fs = scope.finalizers.getAndSet(null)
    val finalizerErrors =
      if fs.isEmpty then Nil
      else
        uninterruptible {
          fs.flatMap { f =>
            try
              f()
              None
            catch case e: Throwable => Some(e)
          }
        }

    result match
      case Left(e)                                 => throwWithSuppressed(e :: finalizerErrors)
      case Right(value) if finalizerErrors.isEmpty => value
      case _                                       => throwWithSuppressed(finalizerErrors)
  end runFinalizers

  /** Waits for `awaitResult` to return a completed computation, retaining every interruption received while waiting. */
  private[ox] def awaitCompletion[T](awaitResult: => Either[Throwable, T]): Awaited[T] =
    var interrupted: InterruptedException = null
    var completed: Either[Throwable, T] = null
    while completed == null do
      try completed = awaitResult
      catch
        case e: InterruptedException =>
          if interrupted == null then interrupted = e
          else interrupted.addSuppressed(e)

    if interrupted != null then completed.left.foreach(interrupted.addSuppressed)
    Awaited(completed, interrupted)
  end awaitCompletion

  /** Resolves a completed uninterruptible wait, rethrowing any interruption captured while waiting. */
  private[ox] def resolve[T](awaited: Awaited[T]): T =
    if awaited.interruption != null then throw awaited.interruption

    awaited.completed.fold(throw _, identity)
  end resolve

  private[ox] def awaitUninterruptibly[T](awaitResult: => Either[Throwable, T]): T =
    resolve(awaitCompletion(awaitResult))
  end awaitUninterruptibly

  private[ox] def onVirtualThread[T](f: => T): T =
    val result = new CompletableFuture[T]()
    Thread
      .ofVirtual()
      .start(() =>
        try
          val _ = result.complete(f)
        catch
          case e: Throwable =>
            val _ = result.completeExceptionally(e)
      )

    def awaitResult: Either[Throwable, T] =
      try Right(result.get())
      catch
        case e: ExecutionException =>
          val cause = e.getCause
          cause.addSuppressed(e)
          Left(cause)
    end awaitResult

    awaitUninterruptibly(awaitResult)
  end onVirtualThread

  private def throwWithSuppressed(es: List[Throwable]): Nothing =
    val e = es.head
    es.tail.foreach(e.addSuppressed)
    throw e
end ResourceRuntime
