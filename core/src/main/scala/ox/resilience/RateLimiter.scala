package ox.resilience

import scala.concurrent.duration.FiniteDuration
import ox.*

import scala.annotation.tailrec

/** Rate Limiter with customizable algorithm. It allows to choose between blocking or dropping an incoming operation. */
class RateLimiter private (algorithm: RateLimiterAlgorithm):
  /** Runs the operation, blocking if the rate limit is reached, until new permits are available. */
  def runBlocking[T](operation: => T): T =
    algorithm.acquire
    operation

  /** Runs or drops the operation, if the rate limit is reached.
    *
    * @return
    *   `Some` if the operation has been allowed to run, `None` if the operation has been dropped.
    */
  def runOrDrop[T](operation: => T): Option[T] =
    if algorithm.tryAcquire then Some(operation)
    else None

end RateLimiter

object RateLimiter:
  def apply(algorithm: RateLimiterAlgorithm)(using Ox): RateLimiter =
    @tailrec
    def update(): Unit =
      val waitTime = algorithm.getNextUpdate
      val millis = waitTime / 1000000
      val nanos = waitTime % 1000000
      Thread.sleep(millis, nanos.toInt)
      algorithm.update
      update()
    end update

    forkDiscard(update())
    new RateLimiter(algorithm)
  end apply

  /** Rate limiter with fixed rate algorithm with possibility to drop or block an operation if not allowed to run.
    *
    * Must be run within an [[Ox]] concurrency scope, as a background thread is created, to replenish the rate limiter.
    *
    * @param maxRequests
    *   Maximum number of requests per consecutive window
    * @param windowSize
    *   Interval of time to pass before reset of the rate limiter
    */
  def fixedRate(maxRequests: Int, windowSize: FiniteDuration)(using Ox): RateLimiter =
    apply(RateLimiterAlgorithm.FixedRate(maxRequests, windowSize))

  /** Rate limiter with sliding window algorithm with possibility to drop or block an operation if not allowed to run.
    *
    * Must be run within an [[Ox]] concurrency scope, as a background thread is created, to replenish the rate limiter.
    *
    * @param maxRequests
    *   Maximum number of requests in any window of time
    * @param windowSize
    *   Size of the window
    */
  def slidingWindow(maxRequests: Int, windowSize: FiniteDuration)(using Ox): RateLimiter =
    apply(RateLimiterAlgorithm.SlidingWindow(maxRequests, windowSize))

  /** Rate limiter with token/leaky bucket algorithm with possibility to drop or block an operation if not allowed to run.
    *
    * Must be run within an [[Ox]] concurrency scope, as a background thread is created, to replenish the rate limiter.
    *
    * @param maxTokens
    *   Max capacity of tokens in the algorithm
    * @param refillInterval
    *   Interval of time after which a token is added
    */
  def bucket(maxTokens: Int, refillInterval: FiniteDuration)(using Ox): RateLimiter =
    apply(RateLimiterAlgorithm.Bucket(maxTokens, refillInterval))
end RateLimiter
