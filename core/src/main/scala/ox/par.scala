package ox

import java.util.concurrent.Semaphore

/** Runs the given computations in parallel. If any fails, interrupts the others, and re-throws the exception. */
def par[T1, T2](t1: => T1, t2: => T2): (T1, T2) =
  val r = par(Vector(() => t1, () => t2)).toVector
  (r(0), r(1)).asInstanceOf[(T1, T2)]

/** Runs the given computations in parallel. If any fails, interrupts the others, and re-throws the exception. */
def par[T1, T2, T3](t1: => T1, t2: => T2, t3: => T3): (T1, T2, T3) =
  val r = par(Vector(() => t1, () => t2, () => t3)).toVector
  (r(0), r(1), r(2)).asInstanceOf[(T1, T2, T3)]

/** Runs the given computations in parallel. If any fails, interrupts the others, and re-throws the exception. */
def par[T1, T2, T3, T4](t1: => T1, t2: => T2, t3: => T3, t4: => T4): (T1, T2, T3, T4) =
  val r = par(Vector(() => t1, () => t2, () => t3, () => t4)).toVector
  (r(0), r(1), r(2), r(3)).asInstanceOf[(T1, T2, T3, T4)]

/** Runs the given computations in parallel. If any fails, interrupts the others, and re-throws the exception. */
def par[T](ts: Seq[() => T]): Seq[T] = par(NoErrorMode)(ts)

/** Runs the given computations in parallel, with at most `parallelism` running in parallel at the same time. If any computation fails,
  * interrupts the others, and re-throws the exception.
  */
def parLimit[T](parallelism: Int)(ts: Seq[() => T]): Seq[T] = parLimit(NoErrorMode)(parallelism)(ts)

//

/** Runs the given computations in parallel. If any fails because of an exception, or if any returns an application error, other
  * computations are interrupted. Then, the exception is re-thrown, or the error value returned. Application errors must be of type `E` in
  * context `F`, and each computation must return an `F`-wrapped value.
  */
def par[E, F[_], T1, T2](em: ErrorMode[E, F])(t1: => F[T1], t2: => F[T2]): F[(T1, T2)] =
  val r = par(em)(Vector(() => t1.asInstanceOf[F[Any]], () => t2.asInstanceOf[F[Any]]))
  if em.isError(r) then r.asInstanceOf[F[(T1, T2)]]
  else
    val rr = em.getT(r)
    em.pure((rr(0), rr(1)).asInstanceOf[(T1, T2)])

/** Runs the given computations in parallel. If any fails because of an exception, or if any returns an application error, other
  * computations are interrupted. Then, the exception is re-thrown, or the error value returned. Application errors must be of type `E` in
  * context `F`, and each computation must return an `F`-wrapped value.
  */
def par[E, F[_], T1, T2, T3](em: ErrorMode[E, F])(t1: => F[T1], t2: => F[T2], t3: => F[T3]): F[(T1, T2, T3)] =
  val r = par(em)(Vector(() => t1.asInstanceOf[F[Any]], () => t2.asInstanceOf[F[Any]], () => t3.asInstanceOf[F[Any]]))
  if em.isError(r) then r.asInstanceOf[F[(T1, T2, T3)]]
  else
    val rr = em.getT(r)
    em.pure((rr(0), rr(1), rr(2)).asInstanceOf[(T1, T2, T3)])

/** Runs the given computations in parallel. If any fails because of an exception, or if any returns an application error, other
  * computations are interrupted. Then, the exception is re-thrown, or the error value returned. Application errors must be of type `E` in
  * context `F`, and each computation must return an `F`-wrapped value.
  */
def par[E, F[_], T1, T2, T3, T4](em: ErrorMode[E, F])(t1: => F[T1], t2: => F[T2], t3: => F[T3], t4: => F[T4]): F[(T1, T2, T3, T4)] =
  val r = par(em)(
    Vector(() => t1.asInstanceOf[F[Any]], () => t2.asInstanceOf[F[Any]], () => t3.asInstanceOf[F[Any]], () => t4.asInstanceOf[F[Any]])
  )
  if em.isError(r) then r.asInstanceOf[F[(T1, T2, T3, T4)]]
  else
    val rr = em.getT(r)
    em.pure((rr(0), rr(1), rr(2), rr(3)).asInstanceOf[(T1, T2, T3, T4)])
end par

/** Runs the given computations in parallel. If any fails because of an exception, or if any returns an application error, other
  * computations are interrupted. Then, the exception is re-thrown, or the error value returned. Application errors must be of type `E` in
  * context `F`, and each computation must return an `F`-wrapped value.
  */
def par[E, F[_], T](em: ErrorMode[E, F])(ts: Seq[() => F[T]]): F[Seq[T]] =
  supervisedError(em) {
    val fs = ts.map(t => forkError(t()))
    em.pure(fs.map(_.join()))
  }

/** Runs the given computations in parallel, with at most `parallelism` running in parallel at the same time. If any computation fails
  * because of an exception, or if any returns an application error, other computations are interrupted. Then, the exception is re-thrown,
  * or the error value returned. Application errors must be of type `E` in context `F`, and each computation must return an `F`-wrapped
  * value.
  */
def parLimit[E, F[_], T](em: ErrorMode[E, F])(parallelism: Int)(ts: Seq[() => F[T]]): F[Seq[T]] =
  supervisedError(em) {
    val s = new Semaphore(parallelism)
    val fs = ts.map(t =>
      forkError {
        s.acquire()
        val r = t()
        // no try-finally as there's no point in releasing in case of an exception, as any newly started forks will be interrupted
        s.release()
        r
      }
    )
    em.pure(fs.map(_.join()))
  }

//

/** Runs the given computations in parallel. If any fails because of an exception, or if any returns a `Left`, other computations are
  * interrupted. Then, the exception is re-thrown, or the `Left` error value returned. Each computation must return an `Either`, with an
  * error type being a subtype of `E`.
  */
def parEither[E, T1, T2](t1: => Either[E, T1], t2: => Either[E, T2]): Either[E, (T1, T2)] =
  val r = parEither(Vector(() => t1, () => t2))
  r.map(rr => (rr(0), rr(1)).asInstanceOf[(T1, T2)])

/** Runs the given computations in parallel. If any fails because of an exception, or if any returns a `Left`, other computations are
  * interrupted. Then, the exception is re-thrown, or the `Left` error value returned. Each computation must return an `Either`, with an
  * error type being a subtype of `E`.
  */
def parEither[E, T1, T2, T3](t1: => Either[E, T1], t2: => Either[E, T3], t3: => Either[E, T3]): Either[E, (T1, T2, T3)] =
  val r = parEither(Vector(() => t1, () => t2, () => t3))
  r.map(rr => (rr(0), rr(1), rr(2)).asInstanceOf[(T1, T2, T3)])

/** Runs the given computations in parallel. If any fails because of an exception, or if any returns a `Left`, other computations are
  * interrupted. Then, the exception is re-thrown, or the `Left` error value returned. Each computation must return an `Either`, with an
  * error type being a subtype of `E`.
  */
def parEither[E, T1, T2, T3, T4](
    t1: => Either[E, T1],
    t2: => Either[E, T3],
    t3: => Either[E, T3],
    t4: => Either[E, T4]
): Either[E, (T1, T2, T3, T4)] =
  val r = parEither(Vector(() => t1, () => t2, () => t3, () => t4))
  r.map(rr => (rr(0), rr(1), rr(2), rr(3)).asInstanceOf[(T1, T2, T3, T4)])
end parEither

/** Runs the given computations in parallel. If any fails because of an exception, or if any returns a `Left`, other computations are
  * interrupted. Then, the exception is re-thrown, or the `Left` error value returned. Each computation must return an `Either`, with an
  * error type being a subtype of `E`.
  */
def parEither[E, T](ts: Seq[() => Either[E, T]]): Either[E, Seq[T]] = par(EitherMode[E])(ts)

/** Runs the given computations in parallel, with at most `parallelism` running in parallel at the same time. If any computation fails
  * because of an exception, or if any returns a `Left`, other computations are interrupted. Then, the exception is re-thrown, or the `Left`
  * error value returned. Each computation must return an `Either`, with an error type being a subtype of `E`.
  */
def parEitherLimit[E, T](parallelism: Int)(ts: Seq[() => Either[E, T]]): Either[E, Seq[T]] = parLimit(EitherMode[E])(parallelism)(ts)
