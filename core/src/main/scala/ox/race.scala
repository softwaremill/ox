package ox

import java.util.concurrent.ArrayBlockingQueue
import scala.annotation.tailrec
import scala.concurrent.TimeoutException
import scala.concurrent.duration.FiniteDuration
import scala.util.{Failure, Success, Try}

/** A `Some` if the computation `t` took less than `duration`, and `None` otherwise. */
def timeoutOption[T](duration: FiniteDuration)(t: => T): Option[T] =
  race(Some(t), { sleep(duration); None })

/** The result of computation `t`, if it took less than `duration`, and a [[TimeoutException]] otherwise.
  * @throws TimeoutException
  *   If `t` took more than `duration`.
  */
def timeout[T](duration: FiniteDuration)(t: => T): T =
  timeoutOption(duration)(t).getOrElse(throw new TimeoutException(s"Timed out after $duration"))

/** Result of the computation `t` if it took less than `duration`, and `Left(timeoutValue)` otherwise. */
def timeoutEither[E, T](duration: FiniteDuration, timeoutValue: E)(t: => Either[E, T]): Either[E, T] =
  timeoutOption(duration)(t).getOrElse(Left(timeoutValue))

//

/** Returns the result of the first computation to complete successfully, or if all fail - throws the first exception. */
def race[T](fs: Seq[() => T]): T = race(NoErrorMode)(fs)

/** Returns the result of the first computation to complete successfully, or if all fail / return an application error - throws the first
  * exception / returns the first application error. Application errors must be of type `E` in context `F`, and each computation must return
  * an `F`-wrapped value.
  */
def race[E, F[_], T](em: ErrorMode[E, F])(fs: Seq[() => F[T]]): F[T] =
  scoped {
    val result = new ArrayBlockingQueue[Try[F[T]]](fs.size)
    fs.foreach(f => forkUnsupervised(result.put(Try(f()))))

    @tailrec
    def takeUntilSuccess(failures: Vector[Either[E, Throwable]], left: Int): F[T] =
      if left == 0 then
        failures.headOption.getOrElse(throw new NoSuchElementException) match
          case Left(appError) =>
            failures.tail.foldLeft(em.pureError(appError)) {
              case (acc, Left(e))  => em.addSuppressedError(acc, e)
              case (acc, Right(e)) => em.addSuppressedException(acc, e)
            }
          case Right(e) =>
            failures.tail.foreach {
              case Left(ee)  => e.addSuppressed(SecondaryApplicationError(ee))
              case Right(ee) => if e != ee then e.addSuppressed(ee)
            }
            throw e
      else
        result.take() match
          case Success(v) =>
            if em.isError(v) then takeUntilSuccess(failures :+ Left(em.getError(v)), left - 1)
            else v
          case Failure(e) => takeUntilSuccess(failures :+ Right(e), left - 1)

    takeUntilSuccess(Vector.empty, fs.size)
  }

/** Returns the result of the first computation to complete successfully, or if all fail - throws the first exception. */
def race[T](f1: => T, f2: => T): T = race(List(() => f1, () => f2))

/** Returns the result of the first computation to complete successfully, or if all fail - throws the first exception. */
def race[T](f1: => T, f2: => T, f3: => T): T = race(List(() => f1, () => f2, () => f3))

/** Returns the result of the first computation to complete successfully, or if all fail - throws the first exception. */
def race[T](f1: => T, f2: => T, f3: => T, f4: => T): T = race(List(() => f1, () => f2, () => f3, () => f4))

/** Returns the result of the first computation to complete successfully, or if all fail / return an application error - throws the first
  * exception / returns the first application error. Application errors must be of type `E` in context `F`, and each computation must return
  * an `F`-wrapped value.
  */
def race[E, F[_], T](em: ErrorMode[E, F])(f1: => F[T], f2: => F[T]): F[T] = race(em)(List(() => f1, () => f2))

/** Returns the result of the first computation to complete successfully, or if all fail / return an application error - throws the first
  * exception / returns the first application error. Application errors must be of type `E` in context `F`, and each computation must return
  * an `F`-wrapped value.
  */
def race[E, F[_], T](em: ErrorMode[E, F])(f1: => F[T], f2: => F[T], f3: => F[T]): F[T] = race(em)(List(() => f1, () => f2, () => f3))

/** Returns the result of the first computation to complete successfully, or if all fail / return an application error - throws the first
  * exception / returns the first application error. Application errors must be of type `E` in context `F`, and each computation must return
  * an `F`-wrapped value.
  */
def race[E, F[_], T](em: ErrorMode[E, F])(f1: => F[T], f2: => F[T], f3: => F[T], f4: => F[T]): F[T] =
  race(em)(List(() => f1, () => f2, () => f3, () => f4))

/** Returns the result of the first computation to complete successfully, or if all fail / return a `Left` - throws the first exception /
  * returns the first `Left`. Each computation must return an `Either`, with an error type being a subtype of `E`.
  */
def raceEither[E, T](f1: => Either[E, T], f2: => Either[E, T]): Either[E, T] = race(EitherMode[E])(List(() => f1, () => f2))

/** Returns the result of the first computation to complete successfully, or if all fail / return a `Left` - throws the first exception /
  * returns the first `Left`. Each computation must return an `Either`, with an error type being a subtype of `E`.
  */
def raceEither[E, T](f1: => Either[E, T], f2: => Either[E, T], f3: => Either[E, T]): Either[E, T] =
  race(EitherMode[E])(List(() => f1, () => f2, () => f3))

/** Returns the result of the first computation to complete successfully, or if all fail / return a `Left` - throws the first exception /
  * returns the first `Left`. Each computation must return an `Either`, with an error type being a subtype of `E`.
  */
def raceEither[E, T](f1: => Either[E, T], f2: => Either[E, T], f3: => Either[E, T], f4: => Either[E, T]): Either[E, T] =
  race(EitherMode[E])(List(() => f1, () => f2, () => f3, () => f4))

//

/** Returns the result of the first computation to complete (either successfully or with an exception). */
def raceResult[T](fs: Seq[() => T]): T = race(fs.map(f => () => Try(f()))).get // TODO optimize

/** Returns the result of the first computation to complete (either successfully or with an exception). */
def raceResult[T](f1: => T, f2: => T): T = raceResult(List(() => f1, () => f2))

/** Returns the result of the first computation to complete (either successfully or with an exception). */
def raceResult[T](f1: => T, f2: => T, f3: => T): T = raceResult(List(() => f1, () => f2, () => f3))

/** Returns the result of the first computation to complete (either successfully or with an exception). */
def raceResult[T](f1: => T, f2: => T, f3: => T, f4: => T): T = raceResult(List(() => f1, () => f2, () => f3, () => f4))
