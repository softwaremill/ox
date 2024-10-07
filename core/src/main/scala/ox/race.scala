package ox

import java.util.concurrent.ArrayBlockingQueue
import scala.annotation.tailrec
import scala.concurrent.TimeoutException
import scala.concurrent.duration.FiniteDuration
import scala.util.control.{ControlThrowable, NonFatal}
import scala.util.{Failure, Success}

/** A `Some` if the computation `t` took less than `duration`, and `None` otherwise. if the computation `t` throws an exception, it is
  * propagated.
  */
def timeoutOption[T](duration: FiniteDuration)(t: => T): Option[T] =
  raceResult(Some(t), { sleep(duration); None })

/** The result of computation `t`, if it took less than `duration`, and a [[TimeoutException]] otherwise. if the computation `t` throws an
  * exception, it is propagated.
  * @throws TimeoutException
  *   If `t` took more than `duration`.
  */
def timeout[T](duration: FiniteDuration)(t: => T): T =
  timeoutOption(duration)(t).getOrElse(throw new TimeoutException(s"Timed out after $duration"))

/** Result of the computation `t` if it took less than `duration`, and `Left(timeoutValue)` otherwise. if the computation `t` throws an
  * exception, it is propagated.
  */
def timeoutEither[E, T](duration: FiniteDuration, timeoutValue: E)(t: => Either[E, T]): Either[E, T] =
  timeoutOption(duration)(t).getOrElse(Left(timeoutValue))

//

/** Returns the result of the first computation to complete successfully, or if all fail - throws the first exception. */
def raceSuccess[T](fs: Seq[() => T]): T = raceSuccess(NoErrorMode)(fs)

/** Returns the result of the first computation to complete successfully, or if all fail / return an application error - throws the first
  * exception / returns the first application error. Application errors must be of type `E` in context `F`, and each computation must return
  * an `F`-wrapped value.
  */
def raceSuccess[E, F[_], T](em: ErrorMode[E, F])(fs: Seq[() => F[T]]): F[T] =
  unsupervised {
    val result = new ArrayBlockingQueue[RaceBranchResult[F[T]]](fs.size)
    fs.foreach(f =>
      forkUnsupervised {
        val r =
          try RaceBranchResult.Success(f())
          catch
            case NonFatal(e) => RaceBranchResult.NonFatalException(e)
            // #213: we treat ControlThrowables as non-fatal, as in the context of `race` they should count as a
            // "failed branch", but not cause immediate interruption
            case e: ControlThrowable => RaceBranchResult.NonFatalException(e)
            // #213: any other fatal exceptions must cause `race` to be interrupted immediately; this is needed as we
            // are in an unsupervised scope, so by default exceptions aren't propagated
            case e => RaceBranchResult.FatalException(e)
        result.put(r)
      }
    )

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
          case RaceBranchResult.Success(v) =>
            if em.isError(v) then takeUntilSuccess(failures :+ Left(em.getError(v)), left - 1)
            else v
          case RaceBranchResult.NonFatalException(e) => takeUntilSuccess(failures :+ Right(e), left - 1)
          case RaceBranchResult.FatalException(e)    => throw e

    takeUntilSuccess(Vector.empty, fs.size)
  }

/** Returns the result of the first computation to complete successfully, or if all fail - throws the first exception. */
def raceSuccess[T](f1: => T, f2: => T): T = raceSuccess(List(() => f1, () => f2))

/** Returns the result of the first computation to complete successfully, or if all fail - throws the first exception. */
def raceSuccess[T](f1: => T, f2: => T, f3: => T): T = raceSuccess(List(() => f1, () => f2, () => f3))

/** Returns the result of the first computation to complete successfully, or if all fail - throws the first exception. */
def raceSuccess[T](f1: => T, f2: => T, f3: => T, f4: => T): T = raceSuccess(List(() => f1, () => f2, () => f3, () => f4))

/** Returns the result of the first computation to complete successfully, or if all fail / return an application error - throws the first
  * exception / returns the first application error. Application errors must be of type `E` in context `F`, and each computation must return
  * an `F`-wrapped value.
  */
def raceSuccess[E, F[_], T](em: ErrorMode[E, F])(f1: => F[T], f2: => F[T]): F[T] = raceSuccess(em)(List(() => f1, () => f2))

/** Returns the result of the first computation to complete successfully, or if all fail / return an application error - throws the first
  * exception / returns the first application error. Application errors must be of type `E` in context `F`, and each computation must return
  * an `F`-wrapped value.
  */
def raceSuccess[E, F[_], T](em: ErrorMode[E, F])(f1: => F[T], f2: => F[T], f3: => F[T]): F[T] =
  raceSuccess(em)(List(() => f1, () => f2, () => f3))

/** Returns the result of the first computation to complete successfully, or if all fail / return an application error - throws the first
  * exception / returns the first application error. Application errors must be of type `E` in context `F`, and each computation must return
  * an `F`-wrapped value.
  */
def raceSuccess[E, F[_], T](em: ErrorMode[E, F])(f1: => F[T], f2: => F[T], f3: => F[T], f4: => F[T]): F[T] =
  raceSuccess(em)(List(() => f1, () => f2, () => f3, () => f4))

/** Returns the result of the first computation to complete successfully, or if all fail / return a `Left` - throws the first exception /
  * returns the first `Left`. Each computation must return an `Either`, with an error type being a subtype of `E`.
  */
def raceEither[E, T](f1: => Either[E, T], f2: => Either[E, T]): Either[E, T] = raceSuccess(EitherMode[E])(List(() => f1, () => f2))

/** Returns the result of the first computation to complete successfully, or if all fail / return a `Left` - throws the first exception /
  * returns the first `Left`. Each computation must return an `Either`, with an error type being a subtype of `E`.
  */
def raceEither[E, T](f1: => Either[E, T], f2: => Either[E, T], f3: => Either[E, T]): Either[E, T] =
  raceSuccess(EitherMode[E])(List(() => f1, () => f2, () => f3))

/** Returns the result of the first computation to complete successfully, or if all fail / return a `Left` - throws the first exception /
  * returns the first `Left`. Each computation must return an `Either`, with an error type being a subtype of `E`.
  */
def raceEither[E, T](f1: => Either[E, T], f2: => Either[E, T], f3: => Either[E, T], f4: => Either[E, T]): Either[E, T] =
  raceSuccess(EitherMode[E])(List(() => f1, () => f2, () => f3, () => f4))

//

/** Returns the result of the first computation to complete (either successfully or with an exception). */
def raceResult[T](fs: Seq[() => T]): T = raceSuccess(
  fs.map(f =>
    () =>
      // #213: the Try() constructor doesn't catch fatal exceptions; in this context, we want to propagate *all*
      // exceptions as fast as possible
      try Success(f())
      catch case e: Throwable => Failure(e)
  )
).get // TODO optimize

/** Returns the result of the first computation to complete (either successfully or with an exception). */
def raceResult[T](f1: => T, f2: => T): T = raceResult(List(() => f1, () => f2))

/** Returns the result of the first computation to complete (either successfully or with an exception). */
def raceResult[T](f1: => T, f2: => T, f3: => T): T = raceResult(List(() => f1, () => f2, () => f3))

/** Returns the result of the first computation to complete (either successfully or with an exception). */
def raceResult[T](f1: => T, f2: => T, f3: => T, f4: => T): T = raceResult(List(() => f1, () => f2, () => f3, () => f4))

private enum RaceBranchResult[+T]:
  case Success(value: T)
  case NonFatalException(throwable: Throwable)
  case FatalException(throwable: Throwable)
