package ox.resilience

import ox.{EitherMode, ErrorMode, sleep}

import scala.annotation.tailrec
import scala.concurrent.duration.*
import scala.util.Try

/** Retries an operation returning a direct result until it succeeds or the policy decides to stop.
  *
  * @param policy
  *   The retry policy - see [[RetryPolicy]].
  * @param operation
  *   The operation to retry.
  * @return
  *   The result of the function if it eventually succeeds.
  * @throws anything
  *   The exception thrown by the last attempt if the policy decides to stop.
  */
def retry[T](policy: RetryPolicy[Throwable, T])(operation: => T): T =
  retryEither(policy)(Try(operation).toEither).fold(throw _, identity)

/** Retries an operation returning an [[scala.util.Either]] until it succeeds or the policy decides to stop. Note that any exceptions thrown
  * by the operation aren't caught and don't cause a retry to happen.
  *
  * @param policy
  *   The retry policy - see [[RetryPolicy]].
  * @param operation
  *   The operation to retry.
  * @return
  *   A [[scala.util.Right]] if the function eventually succeeds, or, otherwise, a [[scala.util.Left]] with the error from the last attempt.
  */
def retryEither[E, T](policy: RetryPolicy[E, T])(operation: => Either[E, T]): Either[E, T] =
  retryWithErrorMode(EitherMode[E])(policy)(operation)

/** Retries an operation using the given error mode until it succeeds or the policy decides to stop. Note that any exceptions thrown by the
  * operation aren't caught (unless the operation catches them as part of its implementation) and don't cause a retry to happen.
  *
  * @param em
  *   The error mode to use, which specifies when a result value is considered success, and when a failure.
  * @param policy
  *   The retry policy - see [[RetryPolicy]].
  * @param operation
  *   The operation to retry.
  * @return
  *   Either:
  *   - the result of the function if it eventually succeeds, in the context of `F`, as dictated by the error mode.
  *   - the error `E` in context `F` as returned by the last attempt if the policy decides to stop.
  */
def retryWithErrorMode[E, F[_], T](em: ErrorMode[E, F])(policy: RetryPolicy[E, T])(operation: => F[T]): F[T] =
  @tailrec
  def loop(attempt: Int, remainingAttempts: Option[Int], lastDelay: Option[FiniteDuration]): F[T] =
    def sleepIfNeeded =
      val delay = policy.schedule.nextDelay(attempt, lastDelay)
      if delay.toMillis > 0 then sleep(delay)
      delay

    operation match
      case v if em.isError(v) =>
        val error = em.getError(v)
        policy.onRetry(attempt, Left(error))

        if policy.resultPolicy.isWorthRetrying(error) && remainingAttempts.forall(_ > 0) then
          val delay = sleepIfNeeded
          loop(attempt + 1, remainingAttempts.map(_ - 1), Some(delay))
        else v
      case v =>
        val result = em.getT(v)
        policy.onRetry(attempt, Right(result))

        if !policy.resultPolicy.isSuccess(result) && remainingAttempts.forall(_ > 0) then
          val delay = sleepIfNeeded
          loop(attempt + 1, remainingAttempts.map(_ - 1), Some(delay))
        else v

  val remainingAttempts = policy.schedule match
    case finiteSchedule: Schedule.Finite => Some(finiteSchedule.maxRetries)
    case _                               => None

  loop(1, remainingAttempts, None)
