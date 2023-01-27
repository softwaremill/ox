package ox

import jdk.incubator.concurrent.{ScopedValue, StructuredTaskScope}

import java.util.concurrent.{Callable, CompletableFuture}
import scala.concurrent.{ExecutionException, TimeoutException}
import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.util.Try

// TODO: implicit not found explaining `scoped`
case class Ox[-T](_scope: StructuredTaskScope[_]) {
  // TODO needed? make _scope private?
  def scope[U <: T]: StructuredTaskScope[U] = _scope.asInstanceOf[StructuredTaskScope[U]]
  def cancel(): Unit = scope.shutdown()
}

object Ox:
  private class DoNothingScope[T] extends StructuredTaskScope[T](null, Thread.ofVirtual().factory()) {}

  /** Any child fibers are interrupted after `f` completes. */
  def scoped[T](f: Ox[Any] ?=> T): T = scopedCustom(new DoNothingScope[Any]()) { scope =>
    try f
    finally
      scope.shutdown()
      scope.join()
  }

  def scopedCustom[T, S <: StructuredTaskScope[T], U](scope: S)(f: S => Ox[T] ?=> U): U =
    try f(scope)(using Ox(scope))
    finally scope.close()

//  trait Sink[T]:
//    def onComplete(r: Either[Throwable, T]): Unit
//
//  def scopedWithSink[T](sink: Sink[T])(t: => T)(using Ox[T]): Fiber[T] = ???

  /** Starts a fiber, which is guaranteed to complete before the enclosing [[scoped]] block exits. */
  def fork[T](f: => T)(using Ox[T]): Fiber[T] =
    val result = new CompletableFuture[T]()
    val forkFuture = summon[Ox[T]].scope.fork { () =>
      try result.complete(f)
      catch case e: Throwable => result.completeExceptionally(e)

      null.asInstanceOf // TODO
    }
    new Fiber[T]:
      override def join(): T = result.get()
      override def cancel(): Either[Throwable, T] =
        forkFuture.cancel(true)
        try Right(result.get())
        catch
          case e: ExecutionException => Left(e.getCause)
          case e: Throwable          => Left(e)

  def forkAll[T](fs: Seq[() => T])(using Ox[T]): Fiber[Seq[T]] =
    val fibers = fs.map(f => fork(f()))
    new Fiber[Seq[T]]:
      override def join(): Seq[T] = fibers.map(_.join())
      override def cancel(): Either[Throwable, Seq[T]] =
        val results = fibers.map(_.cancel())
        if results.exists(_.isLeft)
        then Left(results.collectFirst { case Left(e) => e }.get)
        else Right(results.collect { case Right(t) => t })

  def timeout[T](duration: FiniteDuration)(t: => T): T =
    raceSuccess(Right(t))({ Thread.sleep(duration.toMillis); Left(()) }) match
      case Left(_)  => throw new TimeoutException(s"Timed out after $duration")
      case Right(v) => v

  def raceSuccess[T](fs: Seq[() => T]): T =
    scopedCustom(new StructuredTaskScope.ShutdownOnSuccess[T]()) { scope =>
      fs.foreach(f => scope.fork(() => f()))
      scope.join()
      scope.result()
    }

  def raceResult[T](fs: Seq[() => T]): T = raceSuccess(fs.map(f => () => Try(f()))).get

  /** Returns the result of the first computation to complete successfully, or if all fail - throws the first exception. */
  def raceSuccess[T](f1: => T)(f2: => T): T = raceSuccess(List(() => f1, () => f2))

  /** Returns the result of the first computation to complete (either successfully or with an exception). */
  def raceResult[T](f1: => T)(f2: => T): T = raceResult(List(() => f1, () => f2))

  def uninterruptible[T](f: => T): T =
    scoped {
      val fiber = fork(f)

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

    extension [T](f: => T)(using Ox[T])
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

    /** Interrupts the fiber, and blocks until it completes with a result. */
    def cancel(): Either[Throwable, T]

  private def scopedValueWhere[T, U](sv: ScopedValue[T], t: T)(f: => U): U =
    ScopedValue.where(sv, t, (() => f): Callable[U])

  class FiberLocal[T](scopedValue: ScopedValue[T], default: T):
    def get(): T = scopedValue.orElse(default)

    def scopedWhere[U](newValue: T)(f: Ox[Any] ?=> U): U =
      // the scoped values need to be set inside the thread that's used to create the new scope, but
      // before starting the scope itself, as scoped value bindings can't change after the scope is started
      scopedValueWhere(scopedValue, newValue)(scoped(f))

  object FiberLocal:
    def apply[T](initialValue: T): FiberLocal[T] = new FiberLocal(ScopedValue.newInstance[T](), initialValue)
