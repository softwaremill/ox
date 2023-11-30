package ox.retry

import scala.annotation.tailrec
import scala.concurrent.duration.*
import scala.util.Try

/** Retries an operation returning a direct result until it succeeds or the policy decides to stop.
  *
  * @param operation
  *   The operation to retry.
  * @param policy
  *   The retry policy - see [[RetryPolicy]].
  * @return
  *   The result of the function if it eventually succeeds.
  * @throws anything
  *   The exception thrown by the last attempt if the policy decides to stop.
  */
def retry[T](operation: => T)(policy: RetryPolicy[Throwable, T]): T =
  retry(Try(operation))(policy).get

/** Retries an operation returning a [[scala.util.Try]] until it succeeds or the policy decides to stop.
  *
  * @param operation
  *   The operation to retry.
  * @param policy
  *   The retry policy - see [[RetryPolicy]].
  * @return
  *   A [[scala.util.Success]] if the function eventually succeeds, or, otherwise, a [[scala.util.Failure]] with the error from the last
  *   attempt.
  */
def retry[T](operation: => Try[T])(policy: RetryPolicy[Throwable, T]): Try[T] =
  retry(operation.toEither)(policy).toTry

/** Retries an operation returning an [[scala.util.Either]] until it succeeds or the policy decides to stop.
  *
  * @param operation
  *   The operation to retry.
  * @param policy
  *   The retry policy - see [[RetryPolicy]].
  * @return
  *   A [[scala.util.Right]] if the function eventually succeeds, or, otherwise, a [[scala.util.Left]] with the error from the last attempt.
  */
def retry[E, T](operation: => Either[E, T])(policy: RetryPolicy[E, T]): Either[E, T] =
  @tailrec
  def loop(attempt: Int, remainingAttempts: Option[Int], lastDelay: Option[FiniteDuration]): Either[E, T] =
    def sleepIfNeeded =
      val delay = policy.schedule.nextDelay(attempt + 1, lastDelay).toMillis
      if (delay > 0) Thread.sleep(delay)
      delay

    operation match
      case left @ Left(error) =>
        if policy.resultPolicy.isWorthRetrying(error) && remainingAttempts.forall(_ > 0) then
          val delay = sleepIfNeeded
          loop(attempt + 1, remainingAttempts.map(_ - 1), Some(delay.millis))
        else left
      case right @ Right(result) =>
        if !policy.resultPolicy.isSuccess(result) && remainingAttempts.forall(_ > 0) then
          val delay = sleepIfNeeded
          loop(attempt + 1, remainingAttempts.map(_ - 1), Some(delay.millis))
        else right

  val remainingAttempts = policy.schedule match
    case policy: Schedule.Finite => Some(policy.maxRetries)
    case _                       => None

  loop(0, remainingAttempts, None)
