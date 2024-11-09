# Custom rate limiter
A rate limiter depends on an algorithm controlling whether an operation can be executed and a executor controlling the exact behaviour after rejecting an operation. Algorithms can be customized and used with the existing `RateLimiter` API. The executor can also be customized by using a different API: `GenericRateLimiter`.

## Generic rate limiter
The generic rate limiter API provides utilities to build rate limiters with custom execution policies. This can be useful to implement more complex policies like throttling of operations or passing parameters to modify the behavior of the rate limiter per operation.

The basic syntax for generic rate limiters is the following:

```scala
val executor = GenericRateLimiter.Executor.Drop()
val algorithm = RateLimiterAlgorithm.FixedRate(2, FiniteDuration(1, "seconds"))
val rateLimiter = GenericRateLimiter(algorithm, rateLimiter)
type T
def operation: T = ???

// internally, existing strategies are imported as implicits
// so you don't need to specify a strategy if there is only one for the given executor
val result: Some[T] = rateLimiter(operation)
```

You can also specify the desired execution strategy:

```scala
val executor = GenericRateLimiter.Executor.BlockOrDrop()
val algorithm = RateLimiterAlgorithm.FixedRate(2, FiniteDuration(1, "seconds"))
val rateLimiter = GenericRateLimiter(algorithm, rateLimiter)
type T
def operation: T = ???

// This doesn't work because the rate limiter doesn't know which strategy to choose for the current executor
//val result: Some[T] = rateLimiter(operation)

val resultDrop: Some[T] = rateLimiter(operation)(using GenericRateLimiter.Strategy.Drop())
val resultBlock: T = rateLimiter(operation)(using GenericRateLimiter.Strategy.Block())
```

Note that customizing the strategies employed, it's possible to have different return types for the same rate limiter.

## Executor

A `GenericRateLimiter` is defined by its executor and by its algorithm:

```scala
case class GenericRateLimiter[Returns[_[_]] <: Strategy[_]](
    executor: Executor[Returns],
    algorithm: RateLimiterAlgorithm
):
  def apply[T, Result[_]](operation: => T)(using Returns[Result]): Result[T]
``` 

The `Executor` and `Strategy` API are as follows:

```scala
sealed trait Strategy[F[*]]:
    def run[T](operation: => T): F[T]

trait Executor[Returns[_[_]] <: Strategy[_]]:
    def execute[T, Result[*]](algorithm: RateLimiterAlgorithm, operation: => T)(using cfg: Returns[Result]): Result[T]
    def run[T, Result[_]](operation: => T)(using cfg: Returns[Result]): Result[T] // calls Strategy.run by default
  end Executor
```

To create a custom executor, you need to define a custom `Returns` higher-kinded trait extending `Strategy` that will codify the possible behaviours of the executor or use one of the predefined. Then, the operations in `Executor` will use the leaf classes extending your custom `Returns` to implement the behaviour. When calling your custom rate limiter, you will need to make available through implicits your desired execution strategy, which can be changed per operation. 

Note that your custom executor should not handle the updating of the `RateLimiterAlgorithm`. This is done automatically by the `GenericRateLimiter`.

### Predefined strategies
`Strategy` is extended by three traits:
- Trait `Strategy.Blocking` gives the same return type as the operation to be executed.
- Trait `Strategy.Dropping` gives an `Option[T]` for an operation of type `=> T`.
- Trait `Strategy.BlockOrDrop` allows both types of return depending on the subclass employed.

The traits are implemented by `Strategy.Drop()` and `Strategy.Block()`.

### Custom strategies
Custom strategies allow not only for the specification of the type, but also to add other information to the `Executor`. A possible use could be to add a timeout. The strategy could be defined like this:
```scala
type Id[A] = A
sealed trait CustomStrategy[F[*]] extends Strategy[F]
case class DropAfterTimeout(timeout: Long) extends CustomStrategy[Option]:
  def run[T](operation: => T): Option[T] = Some(operation)
case class RunAfterTimeout(timeout: Long) extends CustomStrategy[Id]:
  def run[T](operation: => T): T = operation
```

Another common example is to specify the "size" of an operation. This is useful when execution depends on the size of packages of data received.
```scala
type Id[A] = A
sealed trait CustomStrategy[F[*]] extends Strategy[F]
case class RunWithSize(size: Int) extends CustomStrategy[Id]:
  def run[T](operation: => T): T = operation
```

After defining a custom strategy, you need to create a corresponding executor to use the `GenericRateLimiter` API. A possible implementation for the last example would be:
```scala
case class CustomExecutor() extends Executor[Strategy.CustomStrategy]:
  def execute[T, Result[*]](algorithm: RateLimiterAlgorithm, operation: => T)(using cfg: CustomStrategy[Result]): Result[T] =
    cfg match 
      case RunWithSize(permitsToAcquire) =>
        algorithm.acquire(permitsToAcquire)
        run(operation)
```

## Rate limiter algorithm
The `RateLimiterAlgorithm` employed by `RateLimiter` and `GenericRateLimiter` can be extended to implement new algorithms or modify existing ones. Its interface is modelled like that of a `Semaphore` although the underlying implementation could be different. For best compatibility with existing executors, methods `acquire` and `tryAcquire` should offer the same garanties as Java `Semaphores`.

Aditionally, there are two methods employed by the `GenericRateLimiter` for updating its internal state automatically:
- `def update: Unit`: Updates the internal state of the rate limiter to reflect its current situation.
- `def getNextUpdate: Long`: Returns the time in nanoseconds after which a new `update` needs to be called.
