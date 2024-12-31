package ox.resilience

import ox.scheduling.{ScheduleContinue, ScheduledConfig, SleepMode, scheduledWithErrorMode}
import ox.*

import scala.util.Try

/** Implements "adaptive" retries: every retry costs [[failureCost]] tokens from the bucket, and every success causes [[successReward]]
  * tokens to be added to the bucket. If there are not enought tokens, retry is not attempted.
  *
  * This way retries don't overload a system that is down due to a systemic failure (such as a bug in the code, excessive load etc.):
  * retries will be attempted only as long as there are enought tokens in the bucket, then the load on the downstream system will be reduced
  * so that it can recover. For transient failures (component failure, infrastructure issues etc.), retries still work as expected, as the
  * bucket has enough tokens to cover the cost of multiple retries.
  *
  * Instances of this class are thread-safe and are designed to be shared. Typically, a single instance should be used to proxy access to a
  * single constrained resource.
  *
  * An instance with default parameters can be created using [[AdaptiveRetry.default]].
  *
  * Inspired by:
  *   - [`AdaptiveRetryStrategy`](https://github.com/aws/aws-sdk-java-v2/blob/master/core/retries/src/main/java/software/amazon/awssdk/retries/AdaptiveRetryStrategy.java)
  *     from `aws-sdk-java-v2`
  *   - ["Try again: The tools and techniques behind resilient systems" from re:Invent 2024](https://www.youtube.com/watch?v=rvHd4Y76-fs)
  *
  * @param tokenBucket
  *   Instance of [[TokenBucket]]. As a token bucket is thread-safe, it can be shared between different instances of [[AdaptiveRetry]], e.g.
  *   with a different [[failureCost]].
  * @param failureCost
  *   Number of tokens to take from [[tokenBucket]] when retrying.
  * @param successReward
  *   Number of tokens to add back to [[tokenBucket]] after a successful operation.
  */
case class AdaptiveRetry(
    tokenBucket: TokenBucket,
    failureCost: Int,
    successReward: Int
):
  /** Retries an operation using the given error mode until it succeeds or the config decides to stop. Note that any exceptions thrown by
    * the operation aren't caught (unless the operation catches them as part of its implementation) and don't cause a retry to happen.
    *
    * This is a special case of [[scheduledWithErrorMode]] with a given set of defaults. See [[RetryConfig]] for more details.
    *
    * @param config
    *   The retry config - See [[RetryConfig]].
    * @param shouldPayPenaltyCost
    *   Function to decide if returned result [[T]] should be considered failure in terms of paying cost for retry. Penalty is paid only if
    *   it is decided to retry operation, the penalty will not be paid for successful operation. Defaults to `true`.
    * @param errorMode
    *   The error mode to use, which specifies when a result value is considered success, and when a failure.
    * @param operation
    *   The operation to retry.
    * @tparam E
    *   type of error.
    * @tparam T
    *   type of result of an operation.
    * @tparam F
    *   the context inside which [[E]] or [[T]] are returned.
    * @return
    *   Either:
    *   - the result of the function if it eventually succeeds, in the context of `F`, as dictated by the error mode.
    *   - the error `E` in context `F` as returned by the last attempt if the config decides to stop.
    * @see
    *   [[scheduledWithErrorMode]]
    */
  def retryWithErrorMode[E, T, F[_]](errorMode: ErrorMode[E, F])(
      config: RetryConfig[E, T],
      shouldPayPenaltyCost: T => Boolean = (_: T) => true
  )(operation: => F[T]): F[T] =

    val afterAttempt: (Int, Either[E, T]) => ScheduleContinue = (attemptNum, attempt) =>
      config.onRetry(attemptNum, attempt)
      attempt match
        case Left(value) =>
          // If we want to retry we try to acquire tokens from bucket
          if config.resultPolicy.isWorthRetrying(value) then ScheduleContinue(tokenBucket.tryAcquire(failureCost))
          else ScheduleContinue.No
        case Right(value) =>
          // If we are successful, we release tokens to bucket and end schedule
          if config.resultPolicy.isSuccess(value) then
            tokenBucket.release(successReward)
            ScheduleContinue.No
            // If it is not success we check if we need to acquire tokens, then we check bucket, otherwise we continue
          else if shouldPayPenaltyCost(value) then ScheduleContinue(tokenBucket.tryAcquire(failureCost))
          else ScheduleContinue.Yes
      end match
    end afterAttempt

    val scheduledConfig = ScheduledConfig(
      config.schedule,
      afterAttempt,
      sleepMode = SleepMode.Delay
    )

    scheduledWithErrorMode(errorMode)(scheduledConfig)(operation)
  end retryWithErrorMode

  /** Retries an operation returning an [[scala.util.Either]] until it succeeds or the config decides to stop. Note that any exceptions
    * thrown by the operation aren't caught and don't cause a retry to happen.
    *
    * [[retryEither]] is a special case of [[scheduledWithErrorMode]] with a given set of defaults. See implementations of [[RetryConfig]]
    * for more details.
    *
    * @param config
    *   The retry config - see [[RetryConfig]].
    * @param shouldPayPenaltyCost
    *   Function to decide if returned result [[T]] should be considered failure in terms of paying cost for retry. Penalty is paid only if
    *   it is decided to retry operation, the penalty will not be paid for successful operation.
    * @param operation
    *   The operation to retry.
    * @tparam E
    *   type of error.
    * @tparam T
    *   type of result of an operation.
    * @return
    *   A [[scala.util.Right]] if the function eventually succeeds, or, otherwise, a [[scala.util.Left]] with the error from the last
    *   attempt.
    * @see
    *   [[scheduledEither]]
    */
  def retryEither[E, T](config: RetryConfig[E, T], shouldPayPenaltyCost: T => Boolean = (_: T) => false)(
      operation: => Either[E, T]
  ): Either[E, T] =
    retryWithErrorMode(EitherMode[E])(config, shouldPayPenaltyCost)(operation)

  /** Retries an operation returning a direct result until it succeeds or the config decides to stop.
    *
    * [[retry]] is a special case of [[scheduledWithErrorMode]] with a given set of defaults. See [[RetryConfig]].
    *
    * @param config
    *   The retry config - see [[RetryConfig]].
    * @param shouldPayPenaltyCost
    *   Function to decide if returned result [[T]] should be considered failure in terms of paying cost for retry. Penalty is paid only if
    *   it is decided to retry operation, the penalty will not be paid for successful operation.
    * @param operation
    *   The operation to retry.
    * @return
    *   The result of the function if it eventually succeeds.
    * @throws anything
    *   The exception thrown by the last attempt if the config decides to stop.
    * @see
    *   [[scheduled]]
    */
  def retry[T](config: RetryConfig[Throwable, T], shouldPayPenaltyCost: T => Boolean = (_: T) => false)(operation: => T): T =
    retryWithErrorMode(EitherMode[Throwable])(config, shouldPayPenaltyCost)(Try(operation).toEither).fold(throw _, identity)

end AdaptiveRetry

object AdaptiveRetry:
  def default: AdaptiveRetry = AdaptiveRetry(TokenBucket(500), 5, 1)
