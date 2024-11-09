package ox.resilience

import scala.concurrent.duration.FiniteDuration
import ox.Ox

/** Rate Limiter with customizable algorithm. It allows to choose between blocking or dropping an incoming operation.
  */
case class RateLimiter(
    algorithm: RateLimiterAlgorithm
)(using Ox):
  import GenericRateLimiter.*

  private val rateLimiter =
    GenericRateLimiter(Executor.BlockOrDrop(), algorithm)

  /** Blocks the operation until the rate limiter allows it.
    */
  def runBlocking[T](operation: => T): T = rateLimiter(operation)(using Strategy.Block())

  /** Drops the operation if not allowed by the rate limiter returning `None`.
    */
  def runOrDrop[T](operation: => T): Option[T] = rateLimiter(operation)(using Strategy.Drop())

end RateLimiter

object RateLimiter:

  /** Rate limiter with fixed rate algorithm with possibility to drop or block an operation if not allowed to run
    *
    * @param maxRequests
    *   Maximum number of requests per consecutive window
    * @param windowSize
    *   Interval of time to pass before reset of the rate limiter
    */
  def fixedRate(
      maxRequests: Int,
      windowSize: FiniteDuration
  )(using Ox): RateLimiter =
    RateLimiter(RateLimiterAlgorithm.FixedRate(maxRequests, windowSize))
  end fixedRate

  /** Rate limiter with sliding window algorithm with possibility to drop or block an operation if not allowed to run
    *
    * @param maxRequests
    *   Maximum number of requests in any window of time
    * @param windowSize
    *   Size of the window
    */
  def slidingWindow(
      maxRequests: Int,
      windowSize: FiniteDuration
  )(using Ox): RateLimiter =
    RateLimiter(RateLimiterAlgorithm.SlidingWindow(maxRequests, windowSize))
  end slidingWindow

  /** Rate limiter with token/leaky bucket algorithm with possibility to drop or block an operation if not allowed to run
    *
    * @param maxTokens
    *   Max capacity of tokens in the algorithm
    * @param refillInterval
    *   Interval of time after which a token is added
    */
  def bucket(
      maxTokens: Int,
      refillInterval: FiniteDuration
  )(using Ox): RateLimiter =
    RateLimiter(RateLimiterAlgorithm.Bucket(maxTokens, refillInterval))
  end bucket
end RateLimiter
