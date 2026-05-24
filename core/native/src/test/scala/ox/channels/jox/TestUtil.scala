package ox.channels.jox

// Ported from: channels/src/test/java/com/softwaremill/jox/TestUtil.java (jox 1.1.2)

import java.util.concurrent.{CompletableFuture, ExecutionException, Future}
import scala.collection.mutable

object TestUtil:
  def scoped(f: Scope => Unit): Unit =
    val scope = new Scope
    val mainTask = Thread.ofVirtual().start(() =>
      try f(scope)
      catch case e: Exception => scope.completeExceptionally(e)
    )
    mainTask.join()
    scope.waitForCompletion()

  def fork[T](scope: Scope, f: () => T): Future[T] =
    val cf = new CompletableFuture[T]()
    Thread.ofVirtual().start(() =>
      try cf.complete(f())
      catch case ex: Exception => cf.completeExceptionally(ex)
    )
    scope.addFuture(cf)
    cf

  def forkVoid(scope: Scope, f: () => Unit): Future[Void] =
    fork(scope, () => { f(); null })

  def forkCancelable[T](scope: Scope, f: () => T): CancelableFork[T] =
    val cf = new CompletableFuture[T]()
    val t = Thread.ofVirtual().start(() =>
      try cf.complete(f())
      catch case ex: Exception => cf.completeExceptionally(ex)
    )
    new CancelableFork(t, cf)

  class Scope:
    private val futures = mutable.ArrayBuffer.empty[CompletableFuture[?]]
    @volatile private var exception: Exception | Null = null

    def addFuture(f: CompletableFuture[?]): Unit = synchronized { futures += f }

    def completeExceptionally(e: Exception): Unit = exception = e

    def waitForCompletion(): Unit =
      if exception != null then throw new ExecutionException(exception)
      synchronized {
        for f <- futures do
          try f.get()
          catch case e: ExecutionException =>
            if exception == null then exception = e.getCause.asInstanceOf[Exception]
      }
      if exception != null then throw new ExecutionException(exception)

  class CancelableFork[T](thread: Thread, future: CompletableFuture[T]):
    def get(): T = future.get()
    def cancel(): AnyRef =
      thread.interrupt()
      thread.join()
      if future.isCompletedExceptionally then future.exceptionNow()
      else future.get().asInstanceOf[AnyRef]
end TestUtil
