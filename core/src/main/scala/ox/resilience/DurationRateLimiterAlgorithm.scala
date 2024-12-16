package ox.resilience

import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import scala.annotation.tailrec
import scala.collection.immutable.Queue
import scala.concurrent.duration.FiniteDuration

object DurationRateLimiterAlgorithm:
  /** Fixed window algorithm: allows to run at most `rate` operations in consecutively segments of duration `per`. */
  case class FixedWindow(rate: Int, per: FiniteDuration) extends RateLimiterAlgorithm:
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

    def runOperation[T](operation: => T, permits: Int): T =
      runningOperations.updateAndGet(_ + permits)
      val result = operation
      runningOperations.updateAndGet(current => (current - permits).max(0))
      result

  end FixedWindow

  /** Sliding window algorithm: allows to run at most `rate` operations in the lapse of `per` before current time. */
  case class SlidingWindow(rate: Int, per: FiniteDuration) extends RateLimiterAlgorithm:
    // stores the timestamp and the number of permits acquired after finishing running operation
    private val log = new AtomicReference[Queue[(Long, Int)]](Queue[(Long, Int)]())
    private val semaphore = new Semaphore(rate)

    def acquire(permits: Int): Unit =
      semaphore.acquire(permits)

    def tryAcquire(permits: Int): Boolean =
      semaphore.tryAcquire(permits)

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

    def runOperation[T](operation: => T, permits: Int): T =
      val result = operation
      // Consider end of operation as a point to release permit after `per` passes
      addTimestampToLog(permits)
      result

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
      end match
    end removeRecords

  end SlidingWindow

end DurationRateLimiterAlgorithm
