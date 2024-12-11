package ox.resilience

import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import scala.annotation.tailrec
import scala.collection.immutable.Queue
import scala.concurrent.duration.FiniteDuration

trait DurationRateLimiterAlgorithm extends RateLimiterAlgorithm:

  def startOperation(permits: Int): Unit

  def endOperation(permits: Int): Unit

  final def startOperation(): Unit = startOperation(1)

  final def endOperation(): Unit = endOperation(1)

end DurationRateLimiterAlgorithm

object DurationRateLimiterAlgorithm:
  case class FixedWindow(rate: Int, per: FiniteDuration) extends DurationRateLimiterAlgorithm:
    private val lastUpdate = new AtomicLong(System.nanoTime())
    private val semaphore = new Semaphore(rate)
    private val runningOperations = new AtomicInteger(0)

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
      semaphore.release(rate - semaphore.availablePermits() - runningOperations.get())
    end update

    def startOperation(permits: Int): Unit =
      runningOperations.updateAndGet(_ + permits)
      ()

    def endOperation(permits: Int): Unit =
      runningOperations.updateAndGet(current => (current - permits).max(0))
      ()

  end FixedWindow

  /** Sliding window algorithm: allows to start at most `rate` operations in the lapse of `per` before current time. */
  case class SlidingWindow(rate: Int, per: FiniteDuration) extends DurationRateLimiterAlgorithm:
    // stores the timestamp and the number of permits acquired after calling acquire or tryAcquire successfully
    private val log = new AtomicReference[Queue[(Long, Int)]](Queue[(Long, Int)]())
    private val semaphore = new Semaphore(rate)
    private val runningOperations = new AtomicInteger(0)

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

    def startOperation(permits: Int): Unit =
      runningOperations.updateAndGet(_ + permits)
      ()

    def endOperation(permits: Int): Unit =
      runningOperations.updateAndGet(current => (current - permits).max(0))
      addTimestampToLog(permits)
      ()

    def update(): Unit =
      val now = System.nanoTime()
      // retrieving current queue to append it later if some elements were added concurrently
      val q = log.getAndUpdate(_ => Queue[(Long, Int)]())
      // remove records older than window size
      val qUpdated = removeRecords(q, now)
      // merge old records with the ones concurrently added
      val _ = log.updateAndGet(qNew =>
        qNew.foldLeft(qUpdated) { case (queue, record) =>
          queue.enqueue(record)
        }
      )
    end update

    @tailrec
    private def removeRecords(q: Queue[(Long, Int)], now: Long): Queue[(Long, Int)] =
      q.dequeueOption match
        case None => q
        case Some((head, tail)) =>
          if head._1 + per.toNanos < now then
            val (_, permits) = head
            semaphore.release(0.max(permits - runningOperations.get()))
            removeRecords(tail, now)
          else q

  end SlidingWindow

  /** Token/leaky bucket algorithm It adds a token to start a new operation each `per` with a maximum number of tokens of `rate`. */
  case class LeakyBucket(rate: Int, per: FiniteDuration) extends DurationRateLimiterAlgorithm:
    private val refillInterval = per.toNanos
    private val lastRefillTime = new AtomicLong(System.nanoTime())
    private val semaphore = new Semaphore(1)
    private val runningOperations = AtomicInteger(0)

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
      if (semaphore.availablePermits() + runningOperations.get()) < rate then semaphore.release()

    def startOperation(permits: Int): Unit =
      runningOperations.updateAndGet(_ + permits)
      ()

    def endOperation(permits: Int): Unit =
      runningOperations.updateAndGet(current => (current - permits).max(0))
      ()

  end LeakyBucket

end DurationRateLimiterAlgorithm
