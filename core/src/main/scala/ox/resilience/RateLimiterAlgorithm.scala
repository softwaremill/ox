package ox.resilience

import scala.concurrent.duration.FiniteDuration
import scala.collection.immutable.Queue
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.Semaphore
import scala.annotation.tailrec

/** Determines the algorithm to use for the rate limiter */
trait RateLimiterAlgorithm:

  /** Acquires a permit to execute the operation. This method should block until a permit is available. */
  final def acquire(): Unit =
    acquire(1)

  /** Acquires permits to execute the operation. This method should block until a permit is available. */
  def acquire(permits: Int): Unit

  /** Tries to acquire a permit to execute the operation. This method should not block. */
  final def tryAcquire(): Boolean =
    tryAcquire(1)

  /** Tries to acquire permits to execute the operation. This method should not block. */
  def tryAcquire(permits: Int): Boolean

  /** Updates the internal state of the rate limiter to check whether new operations can be accepted. */
  def update(): Unit

  /** Returns the time in nanoseconds that needs to elapse until the next update. It should not modify internal state. */
  def getNextUpdate: Long

  /** Runs the operation, allowing the algorithm to take into account its duration, if needed. */
  final def runOperation[T](operation: => T): T = runOperation(operation, 1)

  /** Runs the operation, allowing the algorithm to take into account its duration, if needed. */
  def runOperation[T](operation: => T, permits: Int): T

end RateLimiterAlgorithm

/** RateLimiterAlgorithm: decides whether permit for operation can be acquired. Considers only start of an operation.
  */
object RateLimiterAlgorithm:
  /** Fixed window algorithm: allows starting at most `rate` operations in consecutively segments of duration `per`. */
  case class FixedWindow(rate: Int, per: FiniteDuration) extends RateLimiterAlgorithm:
    private val lastUpdate = new AtomicLong(System.nanoTime())
    private val semaphore = new Semaphore(rate)

    def acquire(permits: Int): Unit =
      semaphore.acquire(permits)

    def tryAcquire(permits: Int): Boolean =
      semaphore.tryAcquire(permits)

    def getNextUpdate: Long =
      val waitTime = lastUpdate.get() + per.toNanos - System.nanoTime()
      if waitTime > 0 then waitTime else 0L

    def update(): Unit =
      val now = System.nanoTime()
      lastUpdate.set(now)
      semaphore.release(rate - semaphore.availablePermits())
    end update

    def runOperation[T](operation: => T, permits: Int): T = operation

  end FixedWindow

  /** Sliding window algorithm: allows to start at most `rate` operations in the lapse of `per` before current time. */
  case class SlidingWindow(rate: Int, per: FiniteDuration) extends RateLimiterAlgorithm:
    // stores the timestamp and the number of permits acquired after calling acquire or tryAcquire successfully
    private val log = new AtomicReference[Queue[(Long, Int)]](Queue[(Long, Int)]())
    private val semaphore = new Semaphore(rate)

    def acquire(permits: Int): Unit =
      semaphore.acquire(permits)
      addTimestampToLog(permits)

    def tryAcquire(permits: Int): Boolean =
      if semaphore.tryAcquire(permits) then
        addTimestampToLog(permits)
        true
      else false

    private def addTimestampToLog(permits: Int): Unit =
      val now = System.nanoTime()
      log.updateAndGet { q =>
        q.enqueue((now, permits))
      }
      ()

    def getNextUpdate: Long =
      log.get().headOption match
        case None =>
          // no logs so no need to update until `per` has passed
          per.toNanos
        case Some(record) =>
          // oldest log provides the new updating point
          val waitTime = record._1 + per.toNanos - System.nanoTime()
          if waitTime > 0 then waitTime else 0L
    end getNextUpdate

    def update(): Unit =
      val now = System.nanoTime()
      // retrieving current queue to append it later if some elements were added concurrently
      val q = log.getAndUpdate(_ => Queue[(Long, Int)]())
      // remove records older than window size
      val qUpdated = removeRecords(q, now)
      // merge old records with the ones concurrently added
      log.updateAndGet(qNew =>
        qNew.foldLeft(qUpdated) { case (queue, record) =>
          queue.enqueue(record)
        }
      )
      ()
    end update

    @tailrec
    private def removeRecords(q: Queue[(Long, Int)], now: Long): Queue[(Long, Int)] =
      q.dequeueOption match
        case None => q
        case Some((head, tail)) =>
          if head._1 + per.toNanos < now then
            val (_, permits) = head
            semaphore.release(permits)
            removeRecords(tail, now)
          else q

    def runOperation[T](operation: => T, permits: Int): T = operation

  end SlidingWindow

  /** Token/leaky bucket algorithm It adds a token to start a new operation each `per` with a maximum number of tokens of `rate`. */
  case class LeakyBucket(rate: Int, per: FiniteDuration) extends RateLimiterAlgorithm:
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

    def update(): Unit =
      val now = System.nanoTime()
      lastRefillTime.set(now)
      if semaphore.availablePermits() < rate then semaphore.release()

    def runOperation[T](operation: => T, permits: Int): T = operation

  end LeakyBucket
end RateLimiterAlgorithm
