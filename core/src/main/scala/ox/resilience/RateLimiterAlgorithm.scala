package ox.resilience

import ox.*
import ox.resilience.RateLimiterAlgorithm.*
import scala.concurrent.duration.*
import ox.scheduling.*
import java.util.concurrent.atomic.{AtomicInteger, AtomicBoolean, AtomicLong}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.*
import scala.util.{Try, Success, Failure}
import javax.swing.text.html.HTML.Tag
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.locks.ReentrantLock

/** Determines the algorithm to use for the rate limiter
  */
trait RateLimiterAlgorithm:

  val blocked = new AtomicBoolean(false)
  def isUnblocked: Boolean = !blocked.get() || tryUnblock

  /** Update internal state to check whether the algorithm can be unblocked.
    */
  def tryUnblock: Boolean

  /** Determines if the operation can be accepted. Implementations should not update internal state. `acceptOperation` and `rejectOperation`
    * are used for updating internal state after accepting or rejecting an operation.
    */
  def isReady: Boolean

  /** Modifies internal state to mark that an operation has been accepted.
    */
  def acceptOperation: Unit

  /** Modifies internal state to mark that an operation has been rejected.
    */
  def rejectOperation: Unit

  /** Returns the time until the next operation can be accepted to be used by the BlockingPolicy. IT should not modify internal state
    */
  def getNextTime(): Long
end RateLimiterAlgorithm

object RateLimiterAlgorithm:
  /** Fixed rate algorithm
    */
  case class FixedRate(rate: Int, per: FiniteDuration) extends RateLimiterAlgorithm:
    private val counter = new AtomicInteger(0)
    private lazy val lastUpdate = new AtomicLong(System.nanoTime())

    def tryUnblock: Boolean =
      if lastUpdate.get() + per.toNanos < System.nanoTime() then
        reset
        true
      else false

    def isReady: Boolean =
      lastUpdate.get()
      counter.get() < rate

    def rejectOperation: Unit =
      blocked.set(true)

    def acceptOperation: Unit =
      counter.incrementAndGet()

    def getNextTime(): Long =
      if isReady then 0
      else lastUpdate.get() + per.toNanos - System.nanoTime()

    private def reset: Unit =
      lastUpdate.set(System.nanoTime())
      counter.set(0)
      blocked.set(false)
  end FixedRate

  /** Sliding window algorithm
    */
  case class SlidingWindow(rate: Int, per: FiniteDuration) extends RateLimiterAlgorithm:
    private val counter = new AtomicInteger(0)
    private val log = new ConcurrentLinkedQueue[Long]()

    def tryUnblock: Boolean =
      val now = System.nanoTime()
      while counter.get() > 0 && log.peek() < now - per.toNanos do
        log.poll()
        counter.decrementAndGet()
      isReady

    def isReady: Boolean =
      counter.get() < rate

    def rejectOperation: Unit = ()

    def acceptOperation: Unit =
      val now = System.nanoTime()
      log.add(now)
      counter.incrementAndGet()

    def getNextTime(): Long =
      if isReady then 0
      else log.peek() + per.toNanos - System.nanoTime()
  end SlidingWindow

  /** Token bucket algorithm
    */
  case class TokenBucket(rate: Int, per: FiniteDuration) extends RateLimiterAlgorithm:
    private val maxTokens = rate
    private val refillInterval = per.toNanos
    private val tokens = new AtomicInteger(1)
    private val lastRefillTime = new AtomicLong(System.nanoTime())

    def tryUnblock: Boolean =
      isReady || refillTokens > 0

    def isReady: Boolean =
      tokens.get() > 0

    def rejectOperation: Unit = ()

    def acceptOperation: Unit =
      tokens.decrementAndGet()

    private def refillTokens: Int =
      val now = System.nanoTime()
      val elapsed = now - lastRefillTime.get()
      val newTokens = elapsed / refillInterval
      tokens.set(Math.min(tokens.get() + newTokens.toInt, maxTokens))
      lastRefillTime.set(newTokens * refillInterval + lastRefillTime.get())
      newTokens.toInt

    def getNextTime(): Long =
      if isReady then 0
      else lastRefillTime.get() + refillInterval - System.nanoTime()

  end TokenBucket

  /** Leaky bucket algorithm
    */
  case class LeakyBucket(capacity: Int, leakRate: FiniteDuration) extends RateLimiterAlgorithm:
    private val counter = new AtomicReference[Double](0.0)
    private val leakInterval = leakRate.toNanos
    private val lastLeakTime = new AtomicLong(System.nanoTime())

    def tryUnblock: Boolean =
      val leaking = leak
      isReady || leaking > 0.0

    def isReady: Boolean =
      counter.get() <= capacity - 1.0

    def rejectOperation: Unit = ()

    def acceptOperation: Unit =
      counter.getAndUpdate(_ + 1.0)

    private def leak: Double =
      val now = System.nanoTime()
      val lastLeak = lastLeakTime.get()
      val elapsed = now - lastLeak
      val leaking: Double = (elapsed.toDouble / leakInterval.toDouble)
      counter.set(Math.max(counter.get() - leaking, 0))
      lastLeakTime.set(now)
      leaking
    end leak

    def getNextTime(): Long =
      if isReady then 0
      else lastLeakTime.get() + leakInterval - System.nanoTime()
  end LeakyBucket
end RateLimiterAlgorithm
