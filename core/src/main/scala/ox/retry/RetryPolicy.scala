package ox.retry

import scala.concurrent.duration.*

/** A policy that defines how to retry a failed operation.
  *
  * @param schedule
  *   The retry schedule which determines the maximum number of retries and the delay between subsequent attempts to execute the operation.
  *   See [[Schedule]] for more details.
  * @param resultPolicy
  *   A policy that allows to customize when a non-erroneous result is considered successful and when an error is worth retrying (which
  *   allows for failing fast on certain errors). See [[ResultPolicy]] for more details.
  * @param onRetry
  *   A function that is invoked after each retry attempt. The callback receives the number of the current retry attempt (starting from 1)
  *   and the result of the operation that was attempted. The result is either a successful value or an error. The callback can be used to
  *   log information about the retry attempts, or to perform other side effects. By default, the callback does nothing.
  * @tparam E
  *   The error type of the operation. For operations returning a `T` or a `Try[T]`, this is fixed to `Throwable`. For operations returning
  *   an `Either[E, T]`, this can be any `E`.
  * @tparam T
  *   The successful result type for the operation.
  */
case class RetryPolicy[E, T](
    schedule: Schedule,
    resultPolicy: ResultPolicy[E, T] = ResultPolicy.default[E, T],
    onRetry: (Int, Either[E, T]) => Unit = (_: Int, _: Either[E, T]) => ()
)

object RetryPolicy:
  /** Creates a policy that retries up to a given number of times, with no delay between subsequent attempts, using a default
    * [[ResultPolicy]].
    *
    * This is a shorthand for {{{RetryPolicy(Schedule.Immediate(maxRetries))}}}
    *
    * @param maxRetries
    *   The maximum number of retries.
    */
  def immediate[E, T](maxRetries: Int): RetryPolicy[E, T] = RetryPolicy(Schedule.Immediate(maxRetries))

  /** Creates a policy that retries indefinitely, with no delay between subsequent attempts, using a default [[ResultPolicy]].
    *
    * This is a shorthand for {{{RetryPolicy(Schedule.Immediate.forever)}}}
    */
  def immediateForever[E, T]: RetryPolicy[E, T] = RetryPolicy(Schedule.Immediate.forever)

  /** Creates a policy that retries up to a given number of times, with a fixed delay between subsequent attempts, using a default
    * [[ResultPolicy]].
    *
    * This is a shorthand for {{{RetryPolicy(Schedule.Delay(maxRetries, delay))}}}
    *
    * @param maxRetries
    *   The maximum number of retries.
    * @param delay
    *   The delay between subsequent attempts.
    */
  def delay[E, T](maxRetries: Int, delay: FiniteDuration): RetryPolicy[E, T] = RetryPolicy(Schedule.Delay(maxRetries, delay))

  /** Creates a policy that retries indefinitely, with a fixed delay between subsequent attempts, using a default [[ResultPolicy]].
    *
    * This is a shorthand for {{{RetryPolicy(Schedule.Delay.forever(delay))}}}
    *
    * @param delay
    *   The delay between subsequent attempts.
    */
  def delayForever[E, T](delay: FiniteDuration): RetryPolicy[E, T] = RetryPolicy(Schedule.Delay.forever(delay))

  /** Creates a policy that retries up to a given number of times, with an increasing delay (backoff) between subsequent attempts, using a
    * default [[ResultPolicy]].
    *
    * The backoff is exponential with base 2 (i.e. the next delay is twice as long as the previous one), starting at the given initial delay
    * and capped at the given maximum delay.
    *
    * This is a shorthand for {{{RetryPolicy(Schedule.Backoff(maxRetries, initialDelay, maxDelay, jitter))}}}
    *
    * @param maxRetries
    *   The maximum number of retries.
    * @param initialDelay
    *   The delay before the first retry.
    * @param maxDelay
    *   The maximum delay between subsequent retries. Defaults to 1 minute.
    * @param jitter
    *   A random factor used for calculating the delay between subsequent retries. See [[Jitter]] for more details. Defaults to no jitter,
    *   i.e. an exponential backoff with no adjustments.
    */
  def backoff[E, T](
      maxRetries: Int,
      initialDelay: FiniteDuration,
      maxDelay: FiniteDuration = 1.minute,
      jitter: Jitter = Jitter.None
  ): RetryPolicy[E, T] =
    RetryPolicy(Schedule.Backoff(maxRetries, initialDelay, maxDelay, jitter))

  /** Creates a policy that retries indefinitely, with an increasing delay (backoff) between subsequent attempts, using a default
    * [[ResultPolicy]].
    *
    * The backoff is exponential with base 2 (i.e. the next delay is twice as long as the previous one), starting at the given initial delay
    * and capped at the given maximum delay.
    *
    * This is a shorthand for {{{RetryPolicy(Schedule.Backoff.forever(initialDelay, maxDelay, jitter))}}}
    *
    * @param initialDelay
    *   The delay before the first retry.
    * @param maxDelay
    *   The maximum delay between subsequent retries. Defaults to 1 minute.
    * @param jitter
    *   A random factor used for calculating the delay between subsequent retries. See [[Jitter]] for more details. Defaults to no jitter,
    *   i.e. an exponential backoff with no adjustments.
    */
  def backoffForever[E, T](
      initialDelay: FiniteDuration,
      maxDelay: FiniteDuration = 1.minute,
      jitter: Jitter = Jitter.None
  ): RetryPolicy[E, T] =
    RetryPolicy(Schedule.Backoff.forever(initialDelay, maxDelay, jitter))
