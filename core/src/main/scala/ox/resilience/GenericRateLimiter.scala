package ox.resilience

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.locks.ReentrantLock
import scala.concurrent.{Await, Future, Promise}
import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.blocking
import GenericRateLimiter.*

case class GenericRateLimiter[Returns[_[_]] <: Strategy[_]](
    executor: Executor[Returns],
    algorithm: RateLimiterAlgorithm
):

  import GenericRateLimiter.Strategy.given

  /** Limits the rate of execution of the given operation
    */
  def apply[T, Result[_]](operation: => T)(using Returns[Result]): Result[T] =
    executor.lock.lock()
    if executor.isUnblocked then
      if algorithm.isUnblocked then
        if algorithm.isReady then
          algorithm.acceptOperation
          executor.lock.unlock()
          executor.run(operation)
        else
          algorithm.rejectOperation
          executor.lock.unlock()
          executor.block(algorithm, operation)
      else
        executor.lock.unlock()
        executor.block(algorithm, operation)
    else
      executor.lock.unlock()
      executor.block(algorithm, operation)
    end if
  end apply
end GenericRateLimiter

object GenericRateLimiter:
  type Id[A] = A
  sealed trait Strategy[F[*]]:
    def run[T](operation: => T): F[T]

  object Strategy:
    sealed trait Blocking[F[*]] extends Strategy[F]
    sealed trait Dropping[F[*]] extends Strategy[F]
    // def drop[T]: F[T]
    sealed trait BlockOrDrop[F[*]] extends Strategy[F]

    case class Block() extends Blocking[Id] with BlockOrDrop[Id]:
      def run[T](operation: => T): T = operation

    case class Drop() extends Dropping[Option] with BlockOrDrop[Option]:
      def run[T](operation: => T): Option[T] = Some(operation)

    given Blocking[Id] = Block()
    given Dropping[Option] = Drop()
  end Strategy

  /** Determines the policy to apply when the rate limiter is full
    */
  trait Executor[Returns[_[_]] <: Strategy[_]]:

    val lock = new ReentrantLock()

    /** This method is called when a new operation can't be readily accepted by the rate limiter. Implementations should execute the
      * operation only if the algorithm and the Executor are both unblocked and they are responsible for checking when the algorithm is
      * ready to accept a new operation, unblocking it and updating its internal state.
      */
    def block[T, Result[_]](algorithm: RateLimiterAlgorithm, operation: => T)(using cfg: Returns[Result]): Result[T]

    /** Runs the operation and returns the result using the given strategy
      */
    def run[T, Result[_]](operation: => T)(using cfg: Returns[Result]): Result[T] =
      cfg.run(operation).asInstanceOf[Result[T]]

    /** Returns whether a new operation will be the first one to be passed to the RateLimiterAlgorithm after unblocking
      */
    def isUnblocked: Boolean
  end Executor

  object Executor:
    /** Block rejected operations until the rate limiter is ready to accept them
      */
    case class Block() extends Executor[Strategy.Blocking]:

      def isUnblocked: Boolean =
        block.peek() == null

      val block = new ConcurrentLinkedQueue[Promise[Unit]]()
      val queueLock = new ReentrantLock()

      def block[T, Result[*]](algorithm: RateLimiterAlgorithm, operation: => T)(using cfg: Strategy.Blocking[Result[*]]): Result[T] =
        // blocks until it can accept current operation and returns next time it will be unblocked
        blockUntilReady(algorithm)

        // updating internal state of algorithm
        lock.lock()
        algorithm.tryUnblock
        lock.unlock()
        algorithm.acceptOperation
        block.poll()

        // fulfilling next promise in queue after waiting time given by algorithm
        fulfillNextPromise(FiniteDuration(algorithm.getNextTime(), "nanoseconds"))

        run(operation)
      end block

      private def blockUntilReady(algorithm: RateLimiterAlgorithm): Unit =
        // creates a promise for the current operation and waits until fulfilled
        queueLock.lock()
        val waitTime =
          if block.peek() == null then Some((algorithm.getNextTime()))
          else None

        val promise = Promise[Unit]()

        block.add(promise)
        queueLock.unlock()

        val future = promise.future
        // if it's not the first promise, it will be fulfilled later
        waitTime.map { wt =>
          fulfillNextPromise(FiniteDuration(wt, "nanoseconds"))
        }

        Await.ready(future, Duration.Inf)
      end blockUntilReady

      private def fulfillNextPromise(waitTime: FiniteDuration): Unit =
        // sleeps waitTime and fulfills next promise in queue
        queueLock.lock()
        if block.peek() != null then
          val p = block.peek()
          queueLock.unlock()
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
        else queueLock.unlock()
        end if
      end fulfillNextPromise
    end Block

    /** Drop rejected operations
      */
    case class Drop() extends Executor[Strategy.Dropping]:
      def isUnblocked: Boolean = true
      def block[T, Result[*]](algorithm: RateLimiterAlgorithm, operation: => T)(using cfg: Strategy.Dropping[Result[*]]): Result[T] =
        lock.lock()
        if algorithm.tryUnblock && algorithm.isReady then
          algorithm.acceptOperation
          lock.unlock()
          cfg.run(operation)
        else
          lock.unlock()
          None.asInstanceOf[Result[T]]
      end block
    end Drop

    /** Block rejected operations until the rate limiter is ready to accept them
      */
    case class BlockOrDrop() extends Executor[Strategy.BlockOrDrop]:

      val blockExecutor = Block()
      val dropExecutor = Drop()

      def isUnblocked: Boolean =
        blockExecutor.isUnblocked

      def block[T, Result[*]](algorithm: RateLimiterAlgorithm, operation: => T)(using cfg: Strategy.BlockOrDrop[Result]): Result[T] =
        cfg match
          case cfg: Strategy.Block =>
            blockExecutor.block(algorithm, operation)(using cfg)
          case cfg: Strategy.Drop =>
            dropExecutor.block(algorithm, operation)(using cfg)

      end block
    end BlockOrDrop

  end Executor
end GenericRateLimiter
