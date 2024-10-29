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
    executor.schedule(algorithm, operation)
    future.map(f => Await.ready(f, Duration.Inf))
    executor.execute(algorithm, operation)
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

    /** Returns a future that will be completed when the operation is execute. It can be used for queueing mechanisms.
      */
    def add[T, Result[*]](algorithm: RateLimiterAlgorithm, operation: => T)(using cfg: Returns[Result]): Option[Future[Unit]]

    /** Ensures that the future returned by `add` can be completed
      */
    def schedule[T, Result[*]](algorithm: RateLimiterAlgorithm, operation: => T)(using cfg: Returns[Result]): Unit

    /** Executes the operation and returns the expected result depending on the strategy
      */
    def execute[T, Result[*]](algorithm: RateLimiterAlgorithm, operation: => T)(using cfg: Returns[Result]): Result[T]

    /** Runs the operation and returns the result using the given strategy
      */
    def run[T, Result[_]](operation: => T)(using cfg: Returns[Result]): Result[T] =
      cfg.run(operation).asInstanceOf[Result[T]]

  end Executor

  object Executor:
    /** Block rejected operations until the rate limiter is ready to accept them
      */
    case class Block(fairness: Boolean = false) extends Executor[Strategy.Blocking]:

      val block = new ConcurrentLinkedQueue[Promise[Unit]]()
      val schedule = new Semaphore(1)

      def add[T, Result[*]](algorithm: RateLimiterAlgorithm, operation: => T)(using
          cfg: Strategy.Blocking[Result[*]]
      ): Option[Future[Unit]] =
        if fairness then
          val promise = Promise[Unit]()
          block.add(promise)
          Some(promise.future)
        else None

      def execute[T, Result[*]](algorithm: RateLimiterAlgorithm, operation: => T)(using cfg: Strategy.Blocking[Result]): Result[T] =
        cfg.run(operation)

      def schedule[T, Result[*]](algorithm: RateLimiterAlgorithm, operation: => T)(using cfg: Strategy.Blocking[Result[*]]): Unit =
        // can't be called with empty queue
        if fairness then
          if algorithm.tryAcquire then
            val p = block.poll()
            p.success(())
          else
            schedule.acquire()
            val wt = algorithm.getNextTime()
            releaseNext(algorithm, FiniteDuration(wt, "nanoseconds"))
        else if !algorithm.tryAcquire then
          if schedule.tryAcquire() then
            val wt = algorithm.getNextTime()
            releaseUnfair(algorithm, FiniteDuration(wt, "nanoseconds"))

            algorithm.acquire
            schedule.release()

            // schedules next release
            val wt2 = algorithm.getNextTime()
            releaseUnfair(algorithm, FiniteDuration(wt2, "nanoseconds"))
          else
            algorithm.acquire
            // schedules next release
            val wt = algorithm.getNextTime()
            releaseUnfair(algorithm, FiniteDuration(wt, "nanoseconds"))

      private def releaseUnfair(algorithm: RateLimiterAlgorithm, waitTime: FiniteDuration): Unit =
        if waitTime.toNanos != 0 then
          Future {
            val wt1 = waitTime.toMillis
            val wt2 = waitTime.toNanos - wt1 * 1000000
            blocking(Thread.sleep(wt1, wt2.toInt))
          }.onComplete { _ =>
            algorithm.reset
          }
        end if
      end releaseUnfair

      private def releaseNext(algorithm: RateLimiterAlgorithm, waitTime: FiniteDuration): Unit =
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
        end if
      end releaseNext
    end Block

    /** Drop rejected operations
      */
    case class Drop() extends Executor[Strategy.Dropping]:

      def add[T, Result[*]](algorithm: RateLimiterAlgorithm, operation: => T)(using
          cfg: Strategy.Dropping[Result[*]]
      ): Option[Future[Unit]] =
        None
      end add

      def schedule[T, Result[*]](algorithm: RateLimiterAlgorithm, operation: => T)(using cfg: Strategy.Dropping[Result[*]]): Unit =
        ()

      def execute[T, Result[*]](algorithm: RateLimiterAlgorithm, operation: => T)(using cfg: Strategy.Dropping[Result[*]]): Result[T] =
        if algorithm.tryAcquire then cfg.run(operation)
        else None.asInstanceOf[Result[T]]

    end Drop

    /** Block rejected operations until the rate limiter is ready to accept them
      */
    case class BlockOrDrop(fairness: Boolean = false) extends Executor[Strategy.BlockOrDrop]:

      val blockExecutor = Block(fairness)
      val dropExecutor = Drop()

      def add[T, Result[*]](algorithm: RateLimiterAlgorithm, operation: => T)(using
          cfg: Strategy.BlockOrDrop[Result]
      ): Option[Future[Unit]] =
        cfg match
          case cfg: Strategy.Block =>
            blockExecutor.add(algorithm, operation)(using cfg.asInstanceOf[Strategy.Blocking[Result]])
          case cfg: Strategy.Drop =>
            dropExecutor.add(algorithm, operation)(using cfg)

      def execute[T, Result[*]](algorithm: RateLimiterAlgorithm, operation: => T)(using cfg: Strategy.BlockOrDrop[Result]): Result[T] =
        cfg match
          case cfg: Strategy.Block =>
            blockExecutor.execute(algorithm, operation)(using cfg.asInstanceOf[Strategy.Blocking[Result]])
          case cfg: Strategy.Drop =>
            dropExecutor.execute(algorithm, operation)(using cfg)

      def schedule[T, Result[*]](algorithm: RateLimiterAlgorithm, operation: => T)(using cfg: Strategy.BlockOrDrop[Result]): Unit =
        cfg match
          case cfg: Strategy.Block =>
            blockExecutor.schedule(algorithm, operation)(using cfg.asInstanceOf[Strategy.Blocking[Result]])
          case cfg: Strategy.Drop =>
            dropExecutor.schedule(algorithm, operation)(using cfg)
    end BlockOrDrop

  end Executor
end GenericRateLimiter
