package ox.resilience

import ox.scheduling.{Schedule, ScheduledConfig, SleepMode}

/** A config that defines how to retry a failed operation.
  *
  * It is a special case of [[ScheduledConfig]] with [[ScheduledConfig.sleepMode]] always set to [[SleepMode.Delay]]. It uses token bucket
  * to determine if operation should be retried. Tokens are taken for every failure and returned on every successful operation, so in case
  * of system failure client does not flood service with retry request.
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
  * @param tokenBucket
  *   Token bucket which backs up adaptive circuit breaker. If bucket is empty, there will be no more retries. Bucket can be provided by
  *   user and shared with different [[AdaptiveRetryConfig]]
  * @param bucketSize
  *   Size of [[TokenBucket]]. Will be ignored if [[tokenBucket]] is provided.
  * @param onFailureCost
  *   Cost of tokens for every failure. It is also number of token added to the bucket for successful operation.
  * @tparam E
  *   The error type of the operation. For operations returning a `T` or a `Try[T]`, this is fixed to `Throwable`. For operations returning
  *   an `Either[E, T]`, this can be any `E`.
  * @tparam T
  *   The successful result type for the operation.
  */
case class AdaptiveRetryConfig[E, T](
    schedule: Schedule,
    resultPolicy: ResultPolicy[E, T] = ResultPolicy.default[E, T],
    onRetry: (Int, Either[E, T]) => Unit = (_: Int, _: Either[E, T]) => (),
    tokenBucket: Option[TokenBucket] = None,
    bucketSize: Int = 100,
    onFailureCost: Int = 1
) extends RetryConfig[E, T]:
  def toScheduledConfig: ScheduledConfig[E, T] =
    val bucket = tokenBucket.getOrElse(TokenBucket(bucketSize))
    def shouldContinueOnError(e: E): Boolean =
      // if we cannot acquire token we short circuit and stop retrying
      bucket.tryAcquire(onFailureCost) && resultPolicy.isWorthRetrying(e)

    def shouldContinueOnResult(result: T): Boolean =
      // if we consider this result as success token are given back to bucket
      if resultPolicy.isSuccess(result) then
        bucket.release(onFailureCost)
        false
      else true

    ScheduledConfig(
      schedule,
      onRetry,
      shouldContinueOnError = shouldContinueOnError,
      shouldContinueOnResult = shouldContinueOnResult,
      sleepMode = SleepMode.Delay
    )
  end toScheduledConfig
end AdaptiveRetryConfig

object AdaptiveRetryConfig:

  /** Creates a config that retries up to a given number of times if there are enough token in the bucket, with no delay between subsequent
    * attempts, using a default [[ResultPolicy]].
    *
    * This is a shorthand for {{{AdaptiveRetryConfig(Schedule.Immediate(maxRetries))}}}
    *
    * @param maxRetries
    *   The maximum number of retries.
    */
  def immediate[E, T](maxRetries: Int, bucketSize: Int = 100): RetryConfig[E, T] =
    AdaptiveRetryConfig(
      Schedule.Immediate(maxRetries),
      bucketSize = bucketSize
    )
end AdaptiveRetryConfig
