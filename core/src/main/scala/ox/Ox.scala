package ox

import jdk.incubator.concurrent.{ScopedValue, StructuredTaskScope}

import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.{ArrayBlockingQueue, Callable, CompletableFuture}
import scala.annotation.tailrec
import scala.concurrent.{ExecutionException, TimeoutException}
import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.util.{Failure, Success, Try}
import scala.util.control.NoStackTrace

// TODO: implicit not found explaining `scoped`
case class Ox(scope: StructuredTaskScope[_], scopeThread: Thread, fiberFailureToPropagate: AtomicReference[Throwable]) {
  def cancel(): Unit = scope.shutdown()
}

object Ox:
  private class DoNothingScope[T] extends StructuredTaskScope[T](null, Thread.ofVirtual().factory()) {}

  /** Any child fibers are interrupted after `f` completes. */
  def scoped[T](f: Ox ?=> T): T =
    val fiberFailure = new AtomicReference[Throwable]()

    // only propagating if the main scope thread was interrupted (presumably because of a supervised child fiber failing)
    def handleInterrupted(e: InterruptedException) = fiberFailure.get() match
      case null => throw e
      case t =>
        t.addSuppressed(e)
        throw t

    val scope = new DoNothingScope[Any]()
    try
      try f(using Ox(scope, Thread.currentThread(), fiberFailure))
      catch case e: InterruptedException => handleInterrupted(e)
      finally
        scope.shutdown()
        scope.join()
    // .join might have been interrupted, because of a fiber failing after f completes, including shutdown
    catch case e: InterruptedException => handleInterrupted(e)
    finally scope.close()

  /** Starts a fiber, which is guaranteed to complete before the enclosing [[scoped]] block exits.
    *
    * Exceptions are held. In case an exception is thrown while evaluating `t`, it will be thrown when calling the returned [[Fiber]]'s
    * `.join()` method. The exception is **not** propagated to the enclosing scope's main thread, like in the case of [[fork]].
    */
  def forkHold[T](f: => T)(using Ox): Fiber[T] =
    val result = new CompletableFuture[T]()
    val forkFuture = summon[Ox].scope.fork { () =>
      try result.complete(f)
      catch case e: Throwable => result.completeExceptionally(e)

      null.asInstanceOf // TODO
    }
    new Fiber[T]:
      override def join(): T = try result.get()
      catch
        case e: ExecutionException => throw e.getCause
        case e: Throwable          => throw e
      override def cancel(): Either[Throwable, T] =
        forkFuture.cancel(true)
        try Right(result.get())
        catch
          case e: ExecutionException => Left(e.getCause)
          case e: Throwable          => Left(e)

  def forkAllHold[T](fs: Seq[() => T])(using Ox): Fiber[Seq[T]] =
    val fibers = fs.map(f => forkHold(f()))
    new Fiber[Seq[T]]:
      override def join(): Seq[T] = fibers.map(_.join())
      override def cancel(): Either[Throwable, Seq[T]] =
        val results = fibers.map(_.cancel())
        if results.exists(_.isLeft)
        then Left(results.collectFirst { case Left(e) => e }.get)
        else Right(results.collect { case Right(t) => t })

  /** Starts a fiber, which is guaranteed to complete before the enclosing [[scoped]] block exits.
    *
    * Exceptions are propagated. In case an exception is thrown while evaluating `t`, the enclosing scope's main thread is interrupted and
    * the exception is re-thrown there.
    */
  def fork[T](f: => T)(using Ox): Fiber[T] = forkHold {
    try f
    catch
      // not propagating interrupts, as these are not failures coming from evaluating `f` itself
      case e: InterruptedException => throw e
      case e: Throwable =>
        val old = summon[Ox].fiberFailureToPropagate.getAndSet(e) // TODO: only the last failure is propagated
        if (old == null) summon[Ox].scopeThread.interrupt()
        throw e
  }

  //

  def timeout[T](duration: FiniteDuration)(t: => T): T =
    raceSuccess(Right(t))({ Thread.sleep(duration.toMillis); Left(()) }) match
      case Left(_)  => throw new TimeoutException(s"Timed out after $duration")
      case Right(v) => v

  def raceSuccess[T](fs: Seq[() => T]): T =
    scoped {
      val result = new ArrayBlockingQueue[Try[T]](fs.size)
      fs.foreach(f => forkHold(result.put(Try(f()))))

      @tailrec
      def takeUntilSuccess(firstException: Option[Throwable], left: Int): T =
        if left == 0 then throw firstException.getOrElse(new NoSuchElementException)
        else
          result.take() match
            case Success(v) => v
            case Failure(e) => takeUntilSuccess(firstException.orElse(Some(e)), left - 1)

      takeUntilSuccess(None, fs.size)
    }

  def raceResult[T](fs: Seq[() => T]): T = raceSuccess(fs.map(f => () => Try(f()))).get // TODO optimize

  /** Returns the result of the first computation to complete successfully, or if all fail - throws the first exception. */
  def raceSuccess[T](f1: => T)(f2: => T): T = raceSuccess(List(() => f1, () => f2))

  /** Returns the result of the first computation to complete (either successfully or with an exception). */
  def raceResult[T](f1: => T)(f2: => T): T = raceResult(List(() => f1, () => f2))

  def uninterruptible[T](f: => T): T =
    scoped {
      val fiber = forkHold(f)

      def joinDespiteInterrupted: T =
        try fiber.join()
        catch
          case e: InterruptedException =>
            joinDespiteInterrupted
            throw e

      joinDespiteInterrupted
    }

  //

  def forever(f: => Unit): Nothing =
    while true do f
    throw new RuntimeException("can't get here")

  def retry[T](times: Int, sleep: FiniteDuration)(f: => T): T =
    try f
    catch
      case e: Throwable =>
        if times > 0
        then
          Thread.sleep(sleep.toMillis)
          retry(times - 1, sleep)(f)
        else throw e

  //

  // errors: .either, .orThrow

  //

  object syntax:
    extension [T](f: => T)
      def forever: Fiber[Nothing] = Ox.forever(f)
      def retry(times: Int, sleep: FiniteDuration): T = Ox.retry(times, sleep)(f)

    extension [T](f: => T)(using Ox)
      def forkHold: Fiber[T] = Ox.forkHold(f)
      def fork: Fiber[T] = Ox.fork(f)
      def timeout(duration: FiniteDuration): T = Ox.timeout(duration)(f)
      def scopedWhere[U](fl: FiberLocal[U], u: U): T = fl.scopedWhere(u)(f)
      def uninterruptible: T = Ox.uninterruptible(f)
      def raceSuccessWith(f2: => T): T = Ox.raceSuccess(f)(f2)
      def raceResultWith(f2: => T): T = Ox.raceResult(f)(f2)

  //

  trait Fiber[T]:
    /** Blocks until the fiber completes with a result. Throws an exception, if the fiber completed with an exception. */
    def join(): T

    /** Blocks until the fiber completes with a result. */
    def joinEither(): Either[Throwable, T] = Try(join()).toEither

    /** Interrupts the fiber, and blocks until it completes with a result. */
    def cancel(): Either[Throwable, T]

  private def scopedValueWhere[T, U](sv: ScopedValue[T], t: T)(f: => U): U =
    ScopedValue.where(sv, t, (() => f): Callable[U])

  class FiberLocal[T](scopedValue: ScopedValue[T], default: T):
    def get(): T = scopedValue.orElse(default)

    def scopedWhere[U](newValue: T)(f: Ox ?=> U): U =
      // the scoped values need to be set inside the thread that's used to create the new scope, but
      // before starting the scope itself, as scoped value bindings can't change after the scope is started
      scopedValueWhere(scopedValue, newValue)(scoped(f))

  object FiberLocal:
    def apply[T](initialValue: T): FiberLocal[T] = new FiberLocal(ScopedValue.newInstance[T](), initialValue)
