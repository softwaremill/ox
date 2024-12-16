package ox.resilience

import scala.concurrent.duration.FiniteDuration
import ox.*

import java.util.concurrent.Semaphore
import scala.annotation.tailrec

/** Rate limiter with a customizable algorithm. Operations can be blocked or dropped, when the rate limit is reached. operationMode decides
  * if whole time of execution should be considered or just the start.
  */
class RateLimiter private (algorithm: RateLimiterAlgorithm):
  /** Runs the operation, blocking if the rate limit is reached, until the rate limiter is replenished. */
  def runBlocking[T](operation: => T): T =
    algorithm.acquire()
    algorithm.runOperation(operation)

  /** Runs or drops the operation, if the rate limit is reached.
    *
    * @return
    *   `Some` if the operation has been allowed to run, `None` if the operation has been dropped.
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

  /** Creates a rate limiter using a fixed window algorithm.
    *
    * Must be run within an [[Ox]] concurrency scope, as a background fork is created, to replenish the rate limiter.
    *
    * @param maxOperations
    *   Maximum number of operations that are allowed to **start** within a time [[window]].
    * @param window
    *   Interval of time between replenishing the rate limiter. The rate limiter is replenished to allow up to [[maxOperations]] in the next
    *   time window.
    */
  def fixedWindow(maxOperations: Int, window: FiniteDuration, operationMode: RateLimiterMode = RateLimiterMode.OperationStart)(using
      Ox
  ): RateLimiter =
    operationMode match
      case RateLimiterMode.OperationStart    => apply(RateLimiterAlgorithm.FixedWindow(maxOperations, window))
      case RateLimiterMode.OperationDuration => apply(DurationRateLimiterAlgorithm.FixedWindow(maxOperations, window))

  /** Creates a rate limiter using a sliding window algorithm.
    *
    * Must be run within an [[Ox]] concurrency scope, as a background fork is created, to replenish the rate limiter.
    *
    * @param maxOperations
    *   Maximum number of operations that are allowed to **start** within any [[window]] of time.
    * @param window
    *   Length of the window.
    */
  def slidingWindow(maxOperations: Int, window: FiniteDuration, operationMode: RateLimiterMode = RateLimiterMode.OperationStart)(using
      Ox
  ): RateLimiter =
    operationMode match
      case RateLimiterMode.OperationStart    => apply(RateLimiterAlgorithm.SlidingWindow(maxOperations, window))
      case RateLimiterMode.OperationDuration => apply(DurationRateLimiterAlgorithm.SlidingWindow(maxOperations, window))

  /** Creates a rate limiter with token/leaky bucket algorithm.
    *
    * Must be run within an [[Ox]] concurrency scope, as a background fork is created, to replenish the rate limiter.
    *
    * @param maxTokens
    *   Max capacity of tokens in the algorithm, limiting the operations that are allowed to **start** concurrently.
    * @param refillInterval
    *   Interval of time between adding a single token to the bucket.
    */
  def leakyBucket(maxTokens: Int, refillInterval: FiniteDuration)(using
      Ox
  ): RateLimiter =
    apply(RateLimiterAlgorithm.LeakyBucket(maxTokens, refillInterval))

end RateLimiter

/** Decides if RateLimiter should consider only start of an operation or whole time of execution.
  */
enum RateLimiterMode:
  case OperationStart
  case OperationDuration
