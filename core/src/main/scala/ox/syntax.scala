package ox

import scala.concurrent.duration.FiniteDuration

object syntax:
  extension [T](f: => T)
    def forever: Fork[Nothing] = ox.forever(f)
    def retry(times: Int, sleep: FiniteDuration): T = ox.retry(times, sleep)(f)

  extension [T](f: => T)(using Ox)
    def fork: Fork[T] = ox.fork(f)
    def forkDaemon: Fork[T] = ox.forkDaemon(f)
    def forkUnsupervised: Fork[T] = ox.forkUnsupervised(f)
    def forkCancellable: CancellableFork[T] = ox.forkCancellable(f)

  extension [T](f: => T)
    def timeout(duration: FiniteDuration): T = ox.timeout(duration)(f)
    def timeoutOption(duration: FiniteDuration): Option[T] = ox.timeoutOption(duration)(f)
    def scopedWhere[U](fl: ForkLocal[U], u: U): T = fl.scopedWhere(u)(f)
    def uninterruptible: T = ox.uninterruptible(f)
    def parWith[U](f2: => U): (T, U) = ox.par(f)(f2)
    def raceSuccessWith(f2: => T): T = ox.raceSuccess(f)(f2)
    def raceResultWith(f2: => T): T = ox.raceResult(f)(f2)

  extension [T <: AutoCloseable](f: => T)(using Ox)
    def useInScope: T = ox.useCloseableInScope(f)
    def useScoped[U](p: T => U): U = ox.useScoped(f)(p)
    def useSupervised[U](p: T => U): U = ox.useSupervised(f)(p)

  extension [I, C[E] <: Iterable[E]](f: => C[I])
    def mapPar[O](parallelism: Int)(transform: I => O) = ox.mapPar(parallelism)(f)(transform)
    def collectPar[O](parallelism: Int)(pf: PartialFunction[I, O]) = ox.collectPar(parallelism)(f)(pf)
    def foreachPar(parallelism: Int)(operation: I => Any) = ox.foreachPar(parallelism)(f)(operation)
    def filterPar(parallelism: Int)(predicate: I => Boolean) = ox.filterPar(parallelism)(f)(predicate)
