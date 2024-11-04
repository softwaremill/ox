package ox.resilience

import ox.*
import ox.resilience.RateLimiterAlgorithm.*
import scala.concurrent.duration.*
import java.util.concurrent.atomic.AtomicLong
import scala.concurrent.*
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.Semaphore
import java.util.{LinkedList, Queue}

/** Determines the algorithm to use for the rate limiter
  */
trait RateLimiterAlgorithm:

  /** Acquire a permit to execute the operation. This method should block until a permit is available.
    */
  def acquire: Unit

  /** Try to acquire a permit to execute the operation. This method should not block.
    */
  def tryAcquire: Boolean

  /** Updates the internal state of the rate limiter to check whether new operations can be accepted.
    */
  def update: Unit

  /** Returns the time until the next operation can be accepted to be used by the `GenericRateLimiter.Executor`. It should return 0 only if
    * there is no need of rescheduling an update in the future. It should not modify internal state.
    */
  def getNextTime(): Long

end RateLimiterAlgorithm

object RateLimiterAlgorithm:
  /** Fixed rate algorithm
    */
  case class FixedRate(rate: Int, per: FiniteDuration) extends RateLimiterAlgorithm:
    private val lastUpdate = new AtomicLong(System.nanoTime())
    private val semaphore = new Semaphore(rate)
    val lock = new java.util.concurrent.locks.ReentrantLock()

    def acquire: Unit =
      semaphore.acquire()

    def tryAcquire: Boolean =
      semaphore.tryAcquire()

    def getNextTime(): Long =
      val waitTime = lastUpdate.get() + per.toNanos - System.nanoTime()
      val q = semaphore.getQueueLength()
      if waitTime > 0 then waitTime
      else if q > 0 then per.toNanos
      else 0L

    def update: Unit =
      val now = System.nanoTime()
      lastUpdate.updateAndGet { time =>
        if time + per.toNanos < now then
          semaphore.drainPermits()
          semaphore.release(rate)
          now
        else time
      }
      ()
    end update

  end FixedRate

  /** Sliding window algorithm
    */
  case class SlidingWindow(rate: Int, per: FiniteDuration) extends RateLimiterAlgorithm:
    private val log = new AtomicReference[Queue[Long]](new LinkedList[Long]())
    private val semaphore = new Semaphore(rate)

    def acquire: Unit =
      semaphore.acquire()
      val now = System.nanoTime()
      log.updateAndGet { q =>
        q.add(now)
        q
      }
      ()
    end acquire

    def tryAcquire: Boolean =
      if semaphore.tryAcquire() then
        val now = System.nanoTime()
        log.updateAndGet { q =>
          q.add(now)
          q
        }
        true
      else false

    def getNextTime(): Long =
      val furtherLog = log.get().peek()
      if null eq furtherLog then
        if semaphore.getQueueLength() > 0 then per.toNanos
        else 0L
      else
        val waitTime = log.get().peek() + per.toNanos - System.nanoTime()
        val q = semaphore.getQueueLength()
        if waitTime > 0 then waitTime
        else if q > 0 then
          update
          getNextTime()
        else 0L
      end if
    end getNextTime

    def update: Unit =
      val now = System.nanoTime()
      while semaphore.availablePermits() < rate && log
          .updateAndGet { q =>
            if q.peek() < now - per.toNanos then
              q.poll()
              semaphore.release()
              q
            else q
          }
          .peek() < now - per.toNanos
      do ()
      end while
    end update

  end SlidingWindow

  /** Token bucket algorithm
    */
  case class TokenBucket(rate: Int, per: FiniteDuration) extends RateLimiterAlgorithm:
    private val refillInterval = per.toNanos
    private val lastRefillTime = new AtomicLong(System.nanoTime())
    private val semaphore = new Semaphore(1)

    def acquire: Unit =
      semaphore.acquire()

    def tryAcquire: Boolean =
      semaphore.tryAcquire()

    def getNextTime(): Long =
      val waitTime = lastRefillTime.get() + refillInterval - System.nanoTime()
      val q = semaphore.getQueueLength()
      if waitTime > 0 then waitTime
      else if q > 0 then refillInterval
      else 0L

    def update: Unit =
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
      semaphore.acquire()

    def tryAcquire: Boolean =
      semaphore.tryAcquire()

    def getNextTime(): Long =
      val waitTime = lastLeakTime.get() + leakInterval - System.nanoTime()
      val q = semaphore.getQueueLength()
      if waitTime > 0 then waitTime
      else if q > 0 then leakInterval
      else 0L

    def update: Unit =
      val now = System.nanoTime()
      val lastLeak = lastLeakTime.get()
      val elapsed = now - lastLeak
      val leaking = elapsed / leakInterval
      val newTime = leaking * leakInterval + lastLeak
      semaphore.release(leaking.toInt)
      lastLeakTime.set(newTime)
    end update

  end LeakyBucket
end RateLimiterAlgorithm
