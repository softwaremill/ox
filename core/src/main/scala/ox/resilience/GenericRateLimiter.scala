package ox.resilience

import GenericRateLimiter.*
import ox.*

/** Rate limiter which allows to pass a configuration value to the execution. This can include both runtime and compile time information,
  * allowing for customization of return types and runtime behavior. If the only behavior needed is to block or drop operations, the
  * `RateLimiter` class provides a simpler interface.
  */
case class GenericRateLimiter[Returns[_[_]] <: Strategy[_]](
    executor: Executor[Returns],
    algorithm: RateLimiterAlgorithm
)(using Ox):

  import GenericRateLimiter.Strategy.given

  val _ =
    fork:
      update()

  /** Limits the rate of execution of the given operation with a custom Result type
    */
  def apply[T, Result[_]](operation: => T)(using Returns[Result]): Result[T] =
    executor.execute(algorithm, operation)
  end apply

  private def update(): Unit =
    val waitTime = algorithm.getNextUpdate
    val millis = waitTime / 1000000
    val nanos = waitTime % 1000000
    Thread.sleep(millis, nanos.toInt)
    algorithm.update
    update()
  end update
end GenericRateLimiter

object GenericRateLimiter:

  type Id[A] = A

  /** Describes the execution strategy that must be used by the rate limiter in a given operation. It allows the encoding of return types
    * and custom runtime behavior.
    */
  trait Strategy[F[*]]:
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

  /** Determines the policy to apply when the rate limiter is full. The executor is responsible of managing the inner state of the algorithm
    * employed. In particular, it must ensure that operations are executed only if allowed and that the algorithm is updated.
    */
  trait Executor[Returns[_[_]] <: Strategy[_]]:
    /** Executes the operation and returns the expected result depending on the strategy. It might perform scheduling tasks if they are not
      * independent from the execution.
      */
    def execute[T, Result[*]](algorithm: RateLimiterAlgorithm, operation: => T)(using Returns[Result]): Result[T]

    /** Runs the operation and returns the result using the given strategy.
      */
    def run[T, Result[_]](operation: => T)(using cfg: Returns[Result]): Result[T] =
      cfg.run(operation).asInstanceOf[Result[T]]

  end Executor

  object Executor:
    /** Block rejected operations until the rate limiter is ready to accept them.
      */
    case class Block() extends Executor[Strategy.Blocking]:
      def execute[T, Result[*]](algorithm: RateLimiterAlgorithm, operation: => T)(using cfg: Strategy.Blocking[Result]): Result[T] =
        algorithm.acquire
        run(operation)

    end Block

    /** Drops rejected operations
      */
    case class Drop() extends Executor[Strategy.Dropping]:
      def execute[T, Result[*]](algorithm: RateLimiterAlgorithm, operation: => T)(using cfg: Strategy.Dropping[Result[*]]): Result[T] =
        if algorithm.tryAcquire then cfg.run(operation)
        else None.asInstanceOf[Result[T]]
    end Drop

    /** Blocks rejected operations until the rate limiter is ready to accept them or drops them depending on the choosen strategy.
      */
    case class BlockOrDrop() extends Executor[Strategy.BlockOrDrop]:

      val blockExecutor = Block()
      val dropExecutor = Drop()

      def execute[T, Result[*]](algorithm: RateLimiterAlgorithm, operation: => T)(using cfg: Strategy.BlockOrDrop[Result]): Result[T] =
        cfg match
          case cfg: Strategy.Block =>
            blockExecutor.execute(algorithm, operation)(using cfg.asInstanceOf[Strategy.Blocking[Result]])
          case cfg: Strategy.Drop =>
            dropExecutor.execute(algorithm, operation)(using cfg)
    end BlockOrDrop

  end Executor
end GenericRateLimiter
