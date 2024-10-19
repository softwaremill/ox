package ox.resilience

import ox.*
import ox.resilience.RateLimiterConfig.*
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

/** Configuration for a rate limiter
  *
  * @param blockingPolicy
  *   blocking policy to apply when the rate limiter is full
  * @param algorithm
  *   algorithm to use for the rate limiter
  */
final case class RateLimiterConfig(
    blockingPolicy: BlockingPolicy,
    algorithm: RateLimiterAlgorithm
):
  def isUnblocked: Boolean =
    algorithm.isUnblocked && blockingPolicy.isUnblocked

  def isReady: Boolean =
    algorithm.isReady

  def acceptOperation: Unit =
    algorithm.acceptOperation

  def block[T](operation: => T): Option[T] =
    blockingPolicy.block(algorithm, operation)
end RateLimiterConfig

object RateLimiterConfig:
  /** Determines the policy to apply when the rate limiter is full
    */
  trait BlockingPolicy:
    /** This method is called when a new operation can't be readily accepted by the rate limiter. Return None for discarded operations, or
      * Some(result) for result of operations after unblocking. Implementations should execute the operation only if the algorithm and the
      * BlockingPolicy are both unblocked and they are responsible for checking when the algorithm is ready to accept a new operation,
      * unblocking it and updating its internal state.
      */
    def block[T](algorithm: RateLimiterAlgorithm, operation: => T): Option[T]

    /** Returns whether a new operation will be the first one to be passed to the RateLimiterAlgorithm after unblocking
      */
    def isUnblocked: Boolean
  end BlockingPolicy

  object BlockingPolicy:

    def apply(blocks: Boolean): BlockingPolicy =
      if blocks then Block() else Drop()

    /** Block rejected operations until the rate limiter is ready to accept them
      */
    case class Block() extends BlockingPolicy:

      def isUnblocked: Boolean =
        block.peek() == null

      val block = new ConcurrentLinkedQueue[Promise[Unit]]()

      def block[T](algorithm: RateLimiterAlgorithm, operation: => T): Option[T] =
        // blocks until it can accept current operation and returns next time it will be unblocked
        blockUntilReady(algorithm, Duration.Inf)

        // updating internal state of algorithm
        algorithm.tryUnblock
        algorithm.acceptOperation
        block.poll()

        // fulfilling next promise in queue after waiting time given by algorithm
        fulfillNextPromise(algorithm, FiniteDuration(algorithm.getNextTime(), "nanoseconds"))

        val result = operation
        Some(result)
      end block

      private def blockUntilReady(algorithm: RateLimiterAlgorithm, timeout: Duration): Unit =
        // creates a promise for the current operation and waits until fulfilled
        val waitTime =
          if block.peek() == null then Some((algorithm.getNextTime()))
          else None
        val promise = Promise[Unit]()

        block.add(promise)
        val future = promise.future
        // if it's not the first promise, it will be fulfilled later
        waitTime.map { wt =>
          fulfillNextPromise(algorithm, FiniteDuration(wt, "nanoseconds"))
        }

        Await.ready(future, timeout)
      end blockUntilReady

      private def fulfillNextPromise(algorithm: RateLimiterAlgorithm, waitTime: FiniteDuration): Unit =
        // sleeps waitTime and fulfills next promise in queue
        if block.peek() != null then
          val p = block.peek()
          if waitTime.toNanos != 0 then
            Future {
              val wt1 = waitTime.toMillis
              val wt2 = waitTime.toNanos - wt1 * 1000000
              blocking(Thread.sleep(wt1, wt2.toInt))
            }.onComplete { _ =>
              p.success(())
            }
          else p.success(())
          end if
    end Block

    /** Drop rejected operations
      */
    case class Drop() extends BlockingPolicy:
      def isUnblocked: Boolean = true
      def block[T](algorithm: RateLimiterAlgorithm, operation: => T): Option[T] =
        if algorithm.tryUnblock && algorithm.isReady then
          algorithm.acceptOperation
          val result = operation
          Some(result)
        else None
    end Drop
  end BlockingPolicy

  /** Determines the algorithm to use for the rate limiter
    */
  trait RateLimiterAlgorithm:

    val blocked = new AtomicBoolean(false)
    def isUnblocked: Boolean = !blocked.get() || tryUnblock

    /** Update internal state to check whether the algorithm can be unblocked.
      */
    def tryUnblock: Boolean

    /** Determines if the operation can be accepted. Implementations should update internal state only to determine if the operation can be
      * accepted, e.g., updating availability after time elapsed. `acceptOperation` and `rejectOperation` are used for updating internal
      * state after accepting or rejecting an operation.
      */
    def isReady: Boolean

    /** Modifies internal state to mark that an operation has been accepted.
      */
    def acceptOperation: Unit

    /** Modifies internal state to mark that an operation has been rejected.
      */
    def rejectOperation: Unit

    /** Returns the time until the next operation can be accepted to be used by the BlockingPolicy
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

      /** Leaky bucket algorithm
        */
    end TokenBucket
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
        lastLeakTime.set(now) // lastLeak + leaking * leakInterval)
        leaking
      end leak

      def getNextTime(): Long =
        if isReady then 0
        else lastLeakTime.get() + leakInterval - System.nanoTime()
    end LeakyBucket
  end RateLimiterAlgorithm

end RateLimiterConfig
