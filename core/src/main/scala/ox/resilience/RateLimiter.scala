package ox.resilience

import scala.concurrent.duration.FiniteDuration
import ox.*
import scala.annotation.tailrec

/** Rate limiter with a customizable algorithm. Operations can be blocked or dropped, when the rate limit is reached. The rate limiter might
  * take into account the start time of the operation, or its entire duration.
  */
class RateLimiter private (algorithm: RateLimiterAlgorithm):
  /** Runs the operation, blocking if the rate limit is reached, until the rate limiter is replenished. */
  def runBlocking[T](operation: => T): T =
    algorithm.acquire()
    algorithm.runOperation(operation)

  /** Runs the operation or drops it, if the rate limit is reached.
    *
    * @return
    *   `Some` if the operation has been run, `None` if the operation has been dropped.
    */
  def runOrDrop[T](operation: => T): Option[T] =
    if algorithm.tryAcquire() then Some(algorithm.runOperation(operation))
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
      algorithm.update()
      update()
    end update

    forkDiscard(update())
    new RateLimiter(algorithm)
  end apply

  /** Creates a rate limiter using a fixed window algorithm. Takes into account the start time of the operation only.
    *
    * Must be run within an [[Ox]] concurrency scope, as a background fork is created, to replenish the rate limiter.
    *
    * @param maxOperations
    *   Maximum number of operations that are allowed to **start** within a time [[window]].
    * @param window
    *   Interval of time between replenishing the rate limiter. The rate limiter is replenished to allow up to [[maxOperations]] in the next
    *   time window.
    * @see
    *   [[durationFixedWindow]]
    */
  def fixedWindow(maxOperations: Int, window: FiniteDuration)(using Ox): RateLimiter =
    apply(StartTimeRateLimiterAlgorithm.FixedWindow(maxOperations, window))

  /** Creates a rate limiter using a sliding window algorithm. Takes into account the start time of the operation only.
    *
    * Must be run within an [[Ox]] concurrency scope, as a background fork is created, to replenish the rate limiter.
    *
    * @param maxOperations
    *   Maximum number of operations that are allowed to **start** within any [[window]] of time.
    * @param window
    *   Length of the window.
    * @see
    *   [[durationSlidingWindow]]
    */
  def slidingWindow(maxOperations: Int, window: FiniteDuration)(using Ox): RateLimiter =
    apply(StartTimeRateLimiterAlgorithm.SlidingWindow(maxOperations, window))

  /** Creates a rate limiter with token/leaky bucket algorithm. Takes into account the start time of the operation only.
    *
    * Must be run within an [[Ox]] concurrency scope, as a background fork is created, to replenish the rate limiter.
    *
    * @param maxTokens
    *   Max capacity of tokens in the algorithm, limiting the operations that are allowed to **start** concurrently.
    * @param refillInterval
    *   Interval of time between adding a single token to the bucket.
    */
  def leakyBucket(maxTokens: Int, refillInterval: FiniteDuration)(using Ox): RateLimiter =
    apply(StartTimeRateLimiterAlgorithm.LeakyBucket(maxTokens, refillInterval))

  /** Creates a rate limiter with a fixed window algorithm.
    *
    * Takes into account the entire duration of the operation. That is the instant at which the operation "happens" can be anywhere between
    * its start and end. This ensures that the rate limit is always respected, although it might make it more restrictive.
    *
    * Must be run within an [[Ox]] concurrency scope, as a background fork is created, to replenish the rate limiter.
    *
    * @param maxOperations
    *   Maximum number of operations that are allowed to **run** (finishing from previous windows or start new) within a time [[window]].
    * @param window
    *   Length of the window.
    * @see
    *   [[fixedWindow]]
    */
  def durationFixedWindow(maxOperations: Int, window: FiniteDuration)(using Ox): RateLimiter =
    apply(DurationRateLimiterAlgorithm.FixedWindow(maxOperations, window))

  /** Creates a rate limiter using a sliding window algorithm.
    *
    * Takes into account the entire duration of the operation. That is the instant at which the operation "happens" can be anywhere between
    * its start and end. This ensures that the rate limit is always respected, although it might make it more restrictive.
    *
    * Must be run within an [[Ox]] concurrency scope, as a background fork is created, to replenish the rate limiter.
    *
    * @param maxOperations
    *   Maximum number of operations that are allowed to **run** (start or finishing) within any [[window]] of time.
    * @param window
    *   Length of the window.
    * @see
    *   [[slidingWindow]]
    */
  def durationSlidingWindow(maxOperations: Int, window: FiniteDuration)(using Ox): RateLimiter =
    apply(DurationRateLimiterAlgorithm.SlidingWindow(maxOperations, window))

end RateLimiter
