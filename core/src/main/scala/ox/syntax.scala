package ox

import scala.concurrent.duration.FiniteDuration

object syntax:
  extension [T](f: => T)
    def forever: Fork[Nothing] = ox.forever(f)
    def retry(times: Int, sleep: FiniteDuration): T = ox.retry(times, sleep)(f)

  extension [T](f: => T)(using Ox)
    def forkHold: Fork[T] = ox.forkHold(f)
    def fork: Fork[T] = ox.fork(f)
    def timeout(duration: FiniteDuration): T = ox.timeout(duration)(f)
    def scopedWhere[U](fl: ForkLocal[U], u: U): T = fl.scopedWhere(u)(f)
    def uninterruptible: T = ox.uninterruptible(f)
    def parWith[U](f2: => U): (T, U) = ox.par(f)(f2)
    def raceSuccessWith(f2: => T): T = ox.raceSuccess(f)(f2)
    def raceResultWith(f2: => T): T = ox.raceResult(f)(f2)

  extension [T <: AutoCloseable](f: => T)(using Ox)
    def useInScope: T = ox.useCloseableInScope(f)
    def useScoped[U](p: T => U): U = ox.useScoped(f)(p)
