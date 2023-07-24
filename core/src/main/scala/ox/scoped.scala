package ox

import jdk.incubator.concurrent.StructuredTaskScope

import java.util.concurrent.atomic.AtomicReference

private class DoNothingScope[T] extends StructuredTaskScope[T](null, Thread.ofVirtual().factory()) {}

/** Any child forks are interrupted after `f` completes. The method only completes when all child forks have completed. */
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
        try f(using Ox(scope, finalizers))
        finally
          scope.shutdown()
          scope.join()
      // join might have been interrupted
      finally scope.close()

    // running the finalizers only once we are sure that all child threads have been terminated, so that no new
    // finalizers are added, and none are lost
    runFinalizers(Right(t))
  catch case e: Throwable => runFinalizers(Left(e))
