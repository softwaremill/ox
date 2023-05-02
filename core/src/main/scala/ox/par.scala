package ox

import java.util.concurrent.Semaphore

/** Runs the given computations in parallel. If any fails, interrupts the others, and re-throws the exception. */
def par[T1, T2](t1: => T1)(t2: => T2): (T1, T2) =
  scoped {
    val f1 = fork(t1)
    val f2 = fork(t2)
    (f1.join(), f2.join())
  }

/** Runs the given computations in parallel. If any fails, interrupts the others, and re-throws the exception. */
def par[T1, T2, T3](t1: => T1)(t2: => T2)(t3: => T3): (T1, T2, T3) =
  scoped {
    val f1 = fork(t1)
    val f2 = fork(t2)
    val f3 = fork(t3)
    (f1.join(), f2.join(), f3.join())
  }

/** Runs the given computations in parallel. If any fails, interrupts the others, and re-throws the exception. */
def par[T](ts: Seq[() => T]): Seq[T] =
  scoped {
    val fs = ts.map(t => fork(t()))
    fs.map(_.join())
  }

/** Runs the given computations in parallel, with at most `concurrency` running concurrently at the same time. If any computation fails,
  * interrupts the others, and re-throws the exception.
  */
def parLimit[T](concurrency: Int)(ts: Seq[() => T]): Seq[T] =
  scoped {
    val s = new Semaphore(concurrency)
    val fs = ts.map(t =>
      fork {
        s.acquire()
        val r = t()
        // no try-finally as there's no point in releasing in case of an exception, as any newly started forks will be interrupted
        s.release()
        r
      }
    )
    fs.map(_.join())
  }
