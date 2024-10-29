package ox.resilience

import ox.*
import ox.resilience.RateLimiterAlgorithm.*
import scala.concurrent.duration.*
import java.util.concurrent.atomic.AtomicLong
import scala.concurrent.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Semaphore

/** Determines the algorithm to use for the rate limiter
  */
trait RateLimiterAlgorithm:

  /** Acquire a permit to execute the operation. This method should block until a permit is available.
    */
  def acquire: Unit

  /** Try to acquire a permit to execute the operation. This method should not block.
    */
  def tryAcquire: Boolean

  /** Returns whether the rate limiter is ready to accept a new operation without modifying internal state.
    */
  def isReady: Boolean

  /** Updates the internal state of the rate limiter to check whether new operations can be accepted.
    */
  def reset: Unit

  /** Returns the time until the next operation can be accepted to be used by the `GenericRateLimiter.Executor`. It should not modify
    * internal state.
    */
  def getNextTime(): Long =
    if isReady then 0
    else computeNextTime()

  /** Compute the time until the next operation can be accepted.
    */
  def computeNextTime(): Long
end RateLimiterAlgorithm

object RateLimiterAlgorithm:
  /** Fixed rate algorithm
    */
  case class FixedRate(rate: Int, per: FiniteDuration) extends RateLimiterAlgorithm:
    private lazy val lastUpdate = new AtomicLong(System.nanoTime())
    private val semaphore = new Semaphore(rate)

    def acquire: Unit =
      tryUnblock
      semaphore.acquire()

    def tryAcquire: Boolean =
      tryUnblock
      semaphore.tryAcquire()

    def isReady: Boolean =
      lastUpdate.get()
      semaphore.availablePermits() > 0

    def computeNextTime(): Long =
      lastUpdate.get() + per.toNanos - System.nanoTime()

    def reset: Unit =
      lastUpdate.set(System.nanoTime())
      semaphore.release(rate)

    private def tryUnblock: Unit =
      if lastUpdate.get() + per.toNanos < System.nanoTime() then reset

  end FixedRate

  /** Sliding window algorithm
    */
  case class SlidingWindow(rate: Int, per: FiniteDuration) extends RateLimiterAlgorithm:
    private val log = new ConcurrentLinkedQueue[Long]()
    private val semaphore = new Semaphore(rate)

    def acquire: Unit =
      tryUnblock
      semaphore.acquire()
      val now = System.nanoTime()
      log.add(now)
      ()

    def tryAcquire: Boolean =
      tryUnblock
      if semaphore.tryAcquire() then
        val now = System.nanoTime()
        log.add(now)
        true
      else false

    def isReady: Boolean =
      semaphore.availablePermits() > 0

    def computeNextTime(): Long =
      log.peek() + per.toNanos - System.nanoTime()

    def reset: Unit =
      tryUnblock

    private def tryUnblock: Unit =
      val now = System.nanoTime()
      while semaphore.availablePermits() < rate && log.peek() < now - per.toNanos do
        log.poll()
        semaphore.release()
        ()
  end SlidingWindow

  /** Token bucket algorithm
    */
  case class TokenBucket(rate: Int, per: FiniteDuration) extends RateLimiterAlgorithm:
    private val refillInterval = per.toNanos
    private val lastRefillTime = new AtomicLong(System.nanoTime())
    private val semaphore = new Semaphore(1)

    def acquire: Unit =
      refillTokens
      semaphore.acquire()

    def tryAcquire: Boolean =
      refillTokens
      semaphore.tryAcquire()

    def isReady: Boolean =
      semaphore.availablePermits() > 0

    def computeNextTime(): Long =
      lastRefillTime.get() + refillInterval - System.nanoTime()

    def reset: Unit =
      refillTokens

    private def refillTokens: Unit =
      val now = System.nanoTime()
      val elapsed = now - lastRefillTime.get()
      val newTokens = elapsed / refillInterval
      lastRefillTime.set(newTokens * refillInterval + lastRefillTime.get())
      semaphore.release(newTokens.toInt)

  end TokenBucket

  /** Leaky bucket algorithm
    */
  case class LeakyBucket(capacity: Int, leakRate: FiniteDuration) extends RateLimiterAlgorithm:
    private val leakInterval = leakRate.toNanos
    private val lastLeakTime = new AtomicLong(System.nanoTime())
    private val semaphore = new Semaphore(capacity)

    def acquire: Unit =
      leak
      semaphore.acquire()

    def tryAcquire: Boolean =
      leak
      semaphore.tryAcquire()

    def isReady: Boolean =
      semaphore.availablePermits() > 0

    def computeNextTime(): Long =
      lastLeakTime.get() + leakInterval - System.nanoTime()

    def reset: Unit =
      leak

    private def leak: Unit =
      val now = System.nanoTime()
      val lastLeak = lastLeakTime.get()
      val elapsed = now - lastLeak
      val leaking = elapsed / leakInterval
      val newTime = leaking * leakInterval + lastLeak
      semaphore.release(leaking.toInt)
      lastLeakTime.set(newTime)
    end leak
  end LeakyBucket
end RateLimiterAlgorithm
