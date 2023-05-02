package ox

import jdk.incubator.concurrent.StructuredTaskScope

import java.util.concurrent.atomic.AtomicReference

private class DoNothingScope[T] extends StructuredTaskScope[T](null, Thread.ofVirtual().factory()) {}

/** Any child forks are interrupted after `f` completes. */
def scoped[T](f: Ox ?=> T): T =
  val forkFailure = new AtomicReference[Throwable]()

  // only propagating if the main scope thread was interrupted (presumably because of a supervised child fork failing)
  def handleInterrupted(e: InterruptedException) = forkFailure.get() match
    case null => throw e
    case t =>
      t.addSuppressed(e)
      throw t

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
        try f(using Ox(scope, Thread.currentThread(), forkFailure, finalizers))
        catch case e: InterruptedException => handleInterrupted(e)
        finally
          scope.shutdown()
          scope.join()
        // .join might have been interrupted, because of a fork failing after f completes, including shutdown
      catch case e: InterruptedException => handleInterrupted(e)
      finally scope.close()

    // running the finalizers only once we are sure that all child threads have been terminated, so that no new
    // finalizers are added, and none are lost
    runFinalizers(Right(t))
  catch case e: Throwable => runFinalizers(Left(e))
