package ox.resilience

import scala.concurrent.duration.*
import RateLimiterConfig.*

/** Configurable rate limiter
  */
case class RateLimiter(
    config: RateLimiterConfig
):
  /** Limits the rate of execution of the given operation
    */
  def apply[T](operation: => T): Option[T] =
    if config.blockingPolicy.isUnblocked then
      if config.algorithm.isUnblocked then
        if config.isReady then
          config.acceptOperation
          val result = operation
          Some(result)
        else
          config.algorithm.rejectOperation
          config.block(operation)
      else config.block(operation)
    else config.block(operation)
end RateLimiter

object RateLimiter:

  def leakyBucket(
      capacity: Int,
      leakInterval: FiniteDuration,
      blocks: Boolean = true
  ): RateLimiter =
    val algorithm = RateLimiterAlgorithm.LeakyBucket(capacity, leakInterval)
    val blockingPolicy = RateLimiterConfig.BlockingPolicy(blocks)
    val config = RateLimiterConfig(blockingPolicy, algorithm)
    RateLimiter(config)
  end leakyBucket

  def tokenBucket(
      maxTokens: Int,
      refillInterval: FiniteDuration,
      blocks: Boolean = true
  ): RateLimiter =
    val algorithm = RateLimiterAlgorithm.TokenBucket(maxTokens, refillInterval)
    val blockingPolicy = RateLimiterConfig.BlockingPolicy(blocks)
    val config = RateLimiterConfig(blockingPolicy, algorithm)
    RateLimiter(config)
  end tokenBucket

  def fixedRate(
      maxRequests: Int,
      windowSize: FiniteDuration,
      blocks: Boolean = true
  ): RateLimiter =
    val algorithm = RateLimiterAlgorithm.FixedRate(maxRequests, windowSize)
    val blockingPolicy = RateLimiterConfig.BlockingPolicy(blocks)
    val config = RateLimiterConfig(blockingPolicy, algorithm)
    RateLimiter(config)
  end fixedRate

  def slidingWindow(
      maxRequests: Int,
      windowSize: FiniteDuration,
      blocks: Boolean = true
  ): RateLimiter =
    val algorithm = RateLimiterAlgorithm.SlidingWindow(maxRequests, windowSize)
    val blockingPolicy = RateLimiterConfig.BlockingPolicy(blocks)
    val config = RateLimiterConfig(blockingPolicy, algorithm)
    RateLimiter(config)
  end slidingWindow

end RateLimiter
