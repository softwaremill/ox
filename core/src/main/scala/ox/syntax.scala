package ox

import ox.retry.RetryPolicy

import scala.concurrent.duration.FiniteDuration

object syntax:
  extension [T](f: => T) def forever: Nothing = ox.forever(f.discard)

  extension [T](f: => T) def retry(policy: RetryPolicy[Throwable, T]): T = ox.retry.retry(policy)(f)
  extension [E, T](f: => Either[E, T]) def retryEither(policy: RetryPolicy[E, T]): Either[E, T] = ox.retry.retryEither(policy)(f)

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
