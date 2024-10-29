package ox.resilience

import scala.concurrent.duration.*

/** Rate Limiter with customizable algorithm. It allows to choose between blocking or dropping an operation.
  */
case class RateLimiter(
    algorithm: RateLimiterAlgorithm,
    fairness: Boolean = false
):
  import GenericRateLimiter.*

  private val rateLimiter = GenericRateLimiter(Executor.BlockOrDrop(fairness), algorithm)

  /** Blocks the operation until the rate limiter allows it.
    */
  def runBlocking[T](operation: => T): T = rateLimiter(operation)(using Strategy.Block())

  /** Drops the operation if not allowed by the rate limiter.
    */
  def runOrDrop[T](operation: => T): Option[T] = rateLimiter(operation)(using Strategy.Drop())

end RateLimiter

object RateLimiter:

  def leakyBucket(
      capacity: Int,
      leakInterval: FiniteDuration,
      fairness: Boolean = false
  ): RateLimiter =
    RateLimiter(RateLimiterAlgorithm.LeakyBucket(capacity, leakInterval), fairness)
  end leakyBucket

  def tokenBucket(
      maxTokens: Int,
      refillInterval: FiniteDuration,
      fairness: Boolean = false
  ): RateLimiter =
    RateLimiter(RateLimiterAlgorithm.TokenBucket(maxTokens, refillInterval), fairness)
  end tokenBucket

  def fixedRate(
      maxRequests: Int,
      windowSize: FiniteDuration,
      fairness: Boolean = false
  ): RateLimiter =
    RateLimiter(RateLimiterAlgorithm.FixedRate(maxRequests, windowSize), fairness)
  end fixedRate

  def slidingWindow(
      maxRequests: Int,
      windowSize: FiniteDuration,
      fairness: Boolean = false
  ): RateLimiter =
    RateLimiter(RateLimiterAlgorithm.SlidingWindow(maxRequests, windowSize), fairness)
  end slidingWindow

end RateLimiter
