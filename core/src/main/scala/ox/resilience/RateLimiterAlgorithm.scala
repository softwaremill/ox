package ox.resilience

import scala.concurrent.duration.FiniteDuration
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.Semaphore
import java.util.{LinkedList, Queue}

/** Determines the algorithm to use for the rate limiter
  */
trait RateLimiterAlgorithm:

  /** Acquires a permit to execute the operation. This method should block until a permit is available.
    */
  final def acquire: Unit =
    acquire(1)

  /** Acquires permits to execute the operation. This method should block until a permit is available.
    */
  def acquire(permits: Int): Unit

  /** Tries to acquire a permit to execute the operation. This method should not block.
    */
  final def tryAcquire: Boolean =
    tryAcquire(1)

  /** Tries to acquire permits to execute the operation. This method should not block.
    */
  def tryAcquire(permits: Int): Boolean

  /** Updates the internal state of the rate limiter to check whether new operations can be accepted.
    */
  def update: Unit

  /** Returns the time in nanoseconds that needs to elapse until the next update. It should not modify internal state.
    */
  def getNextUpdate: Long

end RateLimiterAlgorithm

object RateLimiterAlgorithm:
  /** Fixed rate algorithm It allows starting at most `rate` operations in consecutively segments of duration `per`.
    */
  case class FixedRate(rate: Int, per: FiniteDuration) extends RateLimiterAlgorithm:
    private val lastUpdate = new AtomicLong(System.nanoTime())
    private val semaphore = new Semaphore(rate)

    def acquire(permits: Int): Unit =
      semaphore.acquire(permits)

    def tryAcquire(permits: Int): Boolean =
      semaphore.tryAcquire(permits)

    def getNextUpdate: Long =
      val waitTime = lastUpdate.get() + per.toNanos - System.nanoTime()
      if waitTime > 0 then waitTime else 0L

    def update: Unit =
      val now = System.nanoTime()
      lastUpdate.set(now)
      semaphore.release(rate - semaphore.availablePermits())
    end update

  end FixedRate

  /** Sliding window algorithm It allows to start at most `rate` operations in the lapse of `per` before current time.
    */
  case class SlidingWindow(rate: Int, per: FiniteDuration) extends RateLimiterAlgorithm:
    // stores the timestamp and the number of permits acquired after calling acquire or tryAcquire succesfully
    private val log = new AtomicReference[Queue[(Long, Int)]](new LinkedList[(Long, Int)]())
    private val semaphore = new Semaphore(rate)

    def acquire(permits: Int): Unit =
      semaphore.acquire(permits)
      // adds timestamp to log
      val now = System.nanoTime()
      log.updateAndGet { q =>
        q.add((now, permits))
        q
      }
      ()
    end acquire

    def tryAcquire(permits: Int): Boolean =
      if semaphore.tryAcquire(permits) then
        // adds timestamp to log
        val now = System.nanoTime()
        log.updateAndGet { q =>
          q.add((now, permits))
          q
        }
        true
      else false

    def getNextUpdate: Long =
      if log.get().size() == 0 then
        // no logs so no need to update until `per` has passed
        per.toNanos
      else
        // oldest log provides the new updating point
        val waitTime = log.get().peek()._1 + per.toNanos - System.nanoTime()
        if waitTime > 0 then waitTime else 0L
    end getNextUpdate

    def update: Unit =
      val now = System.nanoTime()
      // retrieving current queue to append it later if some elements were added concurrently
      val q = log.getAndUpdate(_ => new LinkedList[(Long, Int)]())
      // remove records older than window size
      while semaphore.availablePermits() < rate && q.peek()._1 + per.toNanos < now
      do
        val (_, permits) = q.poll()
        semaphore.release(permits)
      // merge old records with the ones concurrently added
      val _ = log.updateAndGet(q2 =>
        val qBefore = q
        while q2.size() > 0
        do
          qBefore.add(q2.poll())
          ()
        qBefore
      )
    end update

  end SlidingWindow

  /** Token/leaky bucket algorithm It adds a token to start an new operation each `per` with a maximum number of tokens of `rate`.
    */
  case class Bucket(rate: Int, per: FiniteDuration) extends RateLimiterAlgorithm:
    private val refillInterval = per.toNanos
    private val lastRefillTime = new AtomicLong(System.nanoTime())
    private val semaphore = new Semaphore(1)

    def acquire(permits: Int): Unit =
      semaphore.acquire(permits)

    def tryAcquire(permits: Int): Boolean =
      semaphore.tryAcquire(permits)

    def getNextUpdate: Long =
      val waitTime = lastRefillTime.get() + refillInterval - System.nanoTime()
      if waitTime > 0 then waitTime else 0L

    def update: Unit =
      val now = System.nanoTime()
      lastRefillTime.set(now)
      if semaphore.availablePermits() < rate then semaphore.release()

  end Bucket
end RateLimiterAlgorithm
