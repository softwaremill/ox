package ox.resilience

import ox.{EitherMode, ErrorMode}
import ox.scheduling.scheduledWithErrorMode

import scala.util.Try

case class AdaptiveRetry(
    tokenBucket: TokenBucket
):
  def apply[E, T, F[_]](
      config: RetryConfig[E, T],
      errorMode: ErrorMode[E, F],
      failureCost: E => Int,
      successReward: T => Int,
      isFailure: E => Boolean
  )(operation: => F[T]): F[T] =
    val isWorthRetrying: E => Boolean = (error: E) =>
      // if we cannot acquire token we short circuit and stop retrying
      val isWorth = config.resultPolicy.isWorthRetrying(error)
      if isWorth && isFailure(error) then tokenBucket.tryAcquire(failureCost(error))
      else isWorth

    val isSuccess: T => Boolean = (result: T) =>
      // if we consider this result as success token are given back to bucket
      if config.resultPolicy.isSuccess(result) then
        tokenBucket.release(successReward(result))
        true
      else false
    end isSuccess

    val resultPolicy = ResultPolicy(isSuccess, isWorthRetrying)
    scheduledWithErrorMode(errorMode)(config.copy(resultPolicy = resultPolicy).toScheduledConfig)(operation)
  end apply

  def apply[E, T](
      config: RetryConfig[E, T],
      failureCost: E => Int = (_: E) => 1,
      successReward: T => Int = (_: T) => 1,
      isFailure: E => Boolean = (_: E) => true
  )(operation: => Either[E, T]): Either[E, T] =
    apply(config, EitherMode[E], failureCost, successReward, isFailure)(operation)

  def apply[T](
      config: RetryConfig[Throwable, T]
  )(operation: => T): T =
    apply(config, EitherMode[Throwable], (_: Throwable) => 1, (_: T) => 1, (_: Throwable) => true)(Try(operation).toEither)
      .fold(throw _, identity)

end AdaptiveRetry
