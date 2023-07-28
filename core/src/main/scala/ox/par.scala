package ox

import java.util.concurrent.{ArrayBlockingQueue, Semaphore}

/** Runs the given computations in parallel. If any fails, interrupts the others, and re-throws the exception. */
def par[T1, T2](t1: => T1)(t2: => T2): (T1, T2) =
  scoped {
    val r = par(Vector(() => t1, () => t2)).toVector
    (r(0), r(1)).asInstanceOf[(T1, T2)]
  }

/** Runs the given computations in parallel. If any fails, interrupts the others, and re-throws the exception. */
def par[T1, T2, T3](t1: => T1)(t2: => T2)(t3: => T3): (T1, T2, T3) =
  scoped {
    val r = par(Vector(() => t1, () => t2, () => t3)).toVector
    (r(0), r(1), r(2)).asInstanceOf[(T1, T2, T3)]
  }

/** Runs the given computations in parallel. If any fails, interrupts the others, and re-throws the exception. */
def par[T](ts: Seq[() => T]): Seq[T] =
  scoped {
    val firstError = new ArrayBlockingQueue[Throwable](1)
    val fs = ts.map(t =>
      fork {
        tapException(t())(firstError.offer)
      }
    )
    joinAllOrFirstError(fs, firstError)
  }

/** Runs the given computations in parallel, with at most `parallelism` running in parallel at the same time. If any computation fails,
  * interrupts the others, and re-throws the exception.
  */
def parLimit[T](parallelism: Int)(ts: Seq[() => T]): Seq[T] =
  scoped {
    val firstError = new ArrayBlockingQueue[Throwable](1)
    val s = new Semaphore(parallelism)
    val fs = ts.map(t =>
      fork {
        s.acquire()
        val r = tapException(t())(firstError.offer)
        // no try-finally as there's no point in releasing in case of an exception, as any newly started forks will be interrupted
        s.release()
        r
      }
    )
    joinAllOrFirstError(fs, firstError)
  }

private def tapException[T](t: => T)(f: Throwable => Unit): T =
  try t
  catch
    case t: Throwable =>
      f(t)
      throw t

private def joinAllOrFirstError[T](fs: Seq[Fork[T]], firstError: ArrayBlockingQueue[Throwable]): Seq[T] = raceResult {
  fs.map(_.join())
} {
  val e = firstError.take()
  fs.map(_.cancel())
  throw e
}
