package ox.resilience

import java.util.concurrent.{ConcurrentLinkedQueue, Semaphore}
import scala.concurrent.{Await, Future, Promise}
import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.blocking
import GenericRateLimiter.*
import ox.resilience.GenericRateLimiter.Strategy.Blocking

case class GenericRateLimiter[Returns[_[_]] <: Strategy[_]](
    executor: Executor[Returns],
    algorithm: RateLimiterAlgorithm
):

  import GenericRateLimiter.Strategy.given

  /** Limits the rate of execution of the given operation with custom Result type
    */
  def apply[T, Result[_]](operation: => T)(using Returns[Result]): Result[T] =
    val future = executor.add(algorithm, operation)
    executor.execute(algorithm, operation)
    Await.result(future, Duration.Inf)
  end apply
end GenericRateLimiter

object GenericRateLimiter:
  
  type Id[A] = A
  
  /** Describe the execution strategy that must be used by the rate limiter in a given operation
    */
  sealed trait Strategy[F[*]]:
    def run[T](operation: => T): F[T]

  object Strategy:
    sealed trait Blocking[F[*]] extends Strategy[F]
    sealed trait Dropping[F[*]] extends Strategy[F]
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

    /** Returns a future that will be completed when the operation is executed
     */
    def add[T, Result[*]](algorithm: RateLimiterAlgorithm, operation: => T)(using cfg: Returns[Result]): Future[Result[T]] 

    /** Ensures that the future returned by `add` is completed 
     */
    def execute[T, Result[*]](algorithm: RateLimiterAlgorithm, operation: => T)(using cfg: Returns[Result]): Unit

    /** Runs the operation and returns the result using the given strategy
      */
    def run[T, Result[_]](operation: => T)(using cfg: Returns[Result]): Result[T] =
      cfg.run(operation).asInstanceOf[Result[T]]

  end Executor

  object Executor:
    /** Block rejected operations until the rate limiter is ready to accept them
      */
    case class Block() extends Executor[Strategy.Blocking]:

      val block = new ConcurrentLinkedQueue[Promise[Unit]]()
      val schedule = new Semaphore(1)

      def add[T, Result[*]](algorithm: RateLimiterAlgorithm, operation: => T)(using cfg: Strategy.Blocking[Result[*]]): Future[Result[T]]  = {
        val promise = Promise[Unit]()
        block.add(promise)
        promise.future.map { _ =>
          run(operation)
        }
      }

      def execute[T, Result[*]](algorithm: RateLimiterAlgorithm, operation: => T)(using cfg: Strategy.Blocking[Result[*]]): Unit = {
        // can't be called with empty queue
        if algorithm.tryAcquire then
          val p = block.poll()
          p.success(())
        else
          schedule.acquire()
          val wt = algorithm.getNextTime()
          fulfillNext(algorithm, FiniteDuration(wt, "nanoseconds"))
      }

      private def fulfillNext(algorithm: RateLimiterAlgorithm, waitTime: FiniteDuration): Unit =
        // sleeps waitTime and fulfills next promise in queue
          val p = block.poll()
          if waitTime.toNanos != 0 then
            Future {
              val wt1 = waitTime.toMillis
              val wt2 = waitTime.toNanos - wt1 * 1000000
              blocking(Thread.sleep(wt1, wt2.toInt))
            }.onComplete { _ =>
              algorithm.acquire
              p.success(())
              schedule.release()
            }
          else
            algorithm.acquire
            p.success(())
            schedule.release()
      end fulfillNext
    end Block

    /** Drop rejected operations
      */
    case class Drop() extends Executor[Strategy.Dropping]:

      def add[T, Result[*]](algorithm: RateLimiterAlgorithm, operation: => T)(using cfg: Strategy.Dropping[Result[*]]): Future[Result[T]] = {
        val promise = Promise[Unit]()
        val f = promise.future
        if algorithm.tryAcquire then promise.success(())
        else promise.failure(new Exception("Rate limiter is full"))
        f.map {
          _ => cfg.run(operation)
        }.recover {
          case e: Exception => None.asInstanceOf[Result[T]]
        }
      }

      def execute[T, Result[*]](algorithm: RateLimiterAlgorithm, operation: => T)(using cfg: Strategy.Dropping[Result[*]]): Unit =
        ()

    end Drop

    /** Block rejected operations until the rate limiter is ready to accept them
      */
    case class BlockOrDrop() extends Executor[Strategy.BlockOrDrop]:

      val blockExecutor = Block()
      val dropExecutor = Drop()

      def add[T, Result[*]](algorithm: RateLimiterAlgorithm, operation: => T)(using cfg: Strategy.BlockOrDrop[Result]): Future[Result[T]] = {

        cfg match
          case cfg: Strategy.Block =>
            blockExecutor.add(algorithm, operation)(using cfg)
          case cfg: Strategy.Drop =>
            dropExecutor.add(algorithm, operation)(using cfg)
      }

      def execute[T, Result[*]](algorithm: RateLimiterAlgorithm, operation: => T)(using cfg: Strategy.BlockOrDrop[Result]): Unit = {
        cfg match
          case cfg: Strategy.Block =>
            blockExecutor.execute(algorithm, operation)(using cfg.asInstanceOf[Strategy.Blocking[Result]])
          case cfg: Strategy.Drop =>
            dropExecutor.execute(algorithm, operation)(using cfg)
      }
    end BlockOrDrop

  end Executor
end GenericRateLimiter
