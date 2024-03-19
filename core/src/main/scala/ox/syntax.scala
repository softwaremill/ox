package ox

import ox.retry.{RetryLifecycle, RetryPolicy}

import scala.concurrent.duration.FiniteDuration

object syntax:
  extension [T](f: => T) def forever: Fork[Nothing] = ox.forever(f)

  extension [T](f: => T)
    def retry(policy: RetryPolicy[Throwable, T], lifecycle: RetryLifecycle[Throwable, T] = RetryLifecycle[Throwable, T]()): T =
      ox.retry.retry(f)(policy, lifecycle)
  extension [E, T](f: => Either[E, T])
    def retryEither(policy: RetryPolicy[E, T], lifecycle: RetryLifecycle[E, T] = RetryLifecycle[E, T]()): Either[E, T] =
      ox.retry.retryEither(f)(policy, lifecycle)

  extension [T](f: => T)(using Ox)
    def forkUser: Fork[T] = ox.forkUser(f)
    def fork: Fork[T] = ox.fork(f)
    def forkUnsupervised: Fork[T] = ox.forkUnsupervised(f)
    def forkCancellable: CancellableFork[T] = ox.forkCancellable(f)

  extension [T](f: => T)
    def timeout(duration: FiniteDuration): T = ox.timeout(duration)(f)
    def timeoutOption(duration: FiniteDuration): Option[T] = ox.timeoutOption(duration)(f)
    def scopedWhere[U](fl: ForkLocal[U], u: U): T = fl.scopedWhere(u)(f)
    def uninterruptible: T = ox.uninterruptible(f)
    def parWith[U](f2: => U): (T, U) = ox.par(f, f2)
    def race(f2: => T): T = ox.race(f, f2)
    def raceResultWith(f2: => T): T = ox.raceResult(f, f2)

  extension [T <: AutoCloseable](f: => T)(using Ox) def useInScope: T = ox.useCloseableInScope(f)

  extension [I, C[E] <: Iterable[E]](f: => C[I])
    def mapPar[O](parallelism: Int)(transform: I => O): C[O] = ox.mapPar(parallelism)(f)(transform)
    def collectPar[O](parallelism: Int)(pf: PartialFunction[I, O]): C[O] = ox.collectPar(parallelism)(f)(pf)
    def foreachPar(parallelism: Int)(operation: I => Any): Unit = ox.foreachPar(parallelism)(f)(operation)
    def filterPar(parallelism: Int)(predicate: I => Boolean): C[I] = ox.filterPar(parallelism)(f)(predicate)
