package warp

import jdk.incubator.concurrent.{ScopedValue, StructuredTaskScope}

import java.util.concurrent.{Callable, CompletableFuture}
import java.util.concurrent.atomic.AtomicReference
import java.util.function.BinaryOperator
import scala.concurrent.Future
import scala.util.Try

object WarpSupervised:
  // used for scoped values inheritance
  // we can use a simple box as starting a new thread is a memory barrier; anything set in the parent, will be
  // visible to the child (https://stackoverflow.com/questions/7128662/does-a-thread-start-causes-a-memory-barrier-shared-variables-will-be-persisted)
  private val currentScope = ScopedValue.newInstance[Box[StructuredTaskScope[Any]]]()

  def apply[T](t: => T): T =
    val box = new Box[StructuredTaskScope[Any]]()
    scopedValueWhere(currentScope, box) {
      val scope = new DoNothingScope[Any]()
      box.set(scope)
      try t
      finally
        scope.join()
        scope.close()
    }

  private trait Where:
    def apply[T](t: => T): T

  private object Where:
    val Noop = new Where:
      def apply[T](t: => T): T = t

  def fork[T](t: => T): Fiber[T] = fork(Where.Noop)(t)

  private class DoNothingScope[T] extends StructuredTaskScope[T](null, Thread.ofVirtual().factory()) {}

  private def fork[T](where: Where)(t: => T): Fiber[T] = {
    val scope = currentScope.get().get()

    val isDone = new CompletableFuture[Unit]()
    val result = new CompletableFuture[T]()
    val f1 = scope.fork { () =>
      try
        val box = new Box[StructuredTaskScope[Any]]()
        where {
          scopedValueWhere(currentScope, box) {
            // starting a new scope, so that when the fiber finishes, we can assure that all children are done
            val cs = new DoNothingScope[Any]
            box.set(cs)
            val innerF = cs.fork(() => t)
            try
              result.complete(innerF.get()) // wait for the inner thread to finish
              cs.shutdown() // interrupt all running fibers
              cs.join()
            catch
              case e: InterruptedException => // propagate the interrupt
                if !result.isDone
                then
                  innerF.cancel(true)
                  val r = cs.join()
                  result.completeExceptionally(e)
                  r
                else cs.join()
            finally cs.close()
          }
        }
      catch case e: Throwable => result.completeExceptionally(e)
      finally isDone.complete(())
    }

    val newChild = new Fiber[T]:
      override def join(): T =
        isDone.get() // wait for the thread to finish
        result.get()
      override def cancel(): Either[Throwable, T] =
        f1.cancel(true) // interrupting the container thread, should interrupt waiting for the result/shutdown and jump to close()
        isDone.get()
        if result.isCompletedExceptionally
        then Left(result.exceptionNow())
        else Right(result.get())

    newChild
  }

  private def scopedValueWhere[T, U](sv: ScopedValue[T], t: T)(f: => U): U =
    ScopedValue.where(sv, t, (() => f): Callable[U])

  def uninterruptible[T](t: => T): T =
    val f = fork(t)

    def joinWhileInterrupted(): T =
      try f.join()
      catch
        case e: InterruptedException =>
          joinWhileInterrupted()
          throw e

    joinWhileInterrupted()

  def retry[T](times: Int, sleep: Long)(t: => T): T =
    try t
    catch
      case e: Exception =>
        if times == 0 then throw e
        else
          Thread.sleep(sleep)
          retry(times - 1, sleep)(t)

  //

  object syntax:
    extension [T](t: => T)
      def fork: Fiber[T] = WarpSupervised.fork(t)
      def forkWhere[U](fl: FiberLocal[U], u: U) = fl.forkWhere(u)(t)
      def uninterruptible: T = WarpSupervised.uninterruptible(t)
      def retry(times: Int, sleep: Long): T = WarpSupervised.retry(times, sleep)(t)

  //

  trait Fiber[T]:
    def join(): T
    def cancel(): Either[Throwable, T]

  class FiberLocal[T](scopedValue: ScopedValue[T], default: T):
    def get(): T = scopedValue.orElse(default)
    def forkWhere[U](newValue: T)(f: => U): Fiber[U] =
      // the scoped values need to be set inside the thread that's used to create the new scope, but
      // before starting the scope itself, as scoped value bindings can't change after the scope is started
      fork(
        new Where:
          def apply[V](t: => V): V =
            scopedValueWhere(scopedValue, newValue)(t)
      )(f)

  object FiberLocal:
    def apply[T](initialValue: T): FiberLocal[T] = new FiberLocal(ScopedValue.newInstance[T](), initialValue)

  //

  private class Box[T]:
    private var value: T = null.asInstanceOf[T]

    def set(v: T): Unit =
      if (value != null) throw new IllegalStateException()
      value = v

    def get(): T = value
