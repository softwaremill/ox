# Custom rate limiter
A rate limiter depends on an algorithm controlling whether an operation can be executed and a executor controlling the exact behaviour after rejecting an operation. The `RateLimiterAlgorithm` can be modified and used with the existing `RateLimiter` API. The executor can also be customized by using a different API: `GenericRateLimiter`.

## Generic rate limiter
The generic rate limiter API provides utility to build rate limiters with custom execution policies. This can be useful to implement more complex policies like throttling of operations.

The basic syntax for generic rate limiters is:

```scala
val executor = GenericRateLimiter.Executor.Drop()
val algorithm = RateLimiterAlgorithm.FixedRate(2, FiniteDuration(1, "seconds"))
val rateLimiter = GenericRateLimiter(algorithm, rateLimiter)
type T
def operation: T = ???

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

Note that modifying the strategy used, it's possible to have different return types for the same rate limiter.

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
    def schedule[T, Result[*]](algorithm: RateLimiterAlgorithm, operation: => T)(using cfg: Returns[Result]): Unit
    def execute[T, Result[*]](algorithm: RateLimiterAlgorithm, operation: => T)(using cfg: Returns[Result]): Result[T]
    def run[T, Result[_]](operation: => T)(using cfg: Returns[Result]): Result[T] // calls Strategy.run
  end Executor
```

To create a custom executor, you need to define a custom `Returns` higher-kinded trait extending `Strategy` that will codify the possible behaviours of the executor or use one of the predefined. Then, the operations in `Executor` will use the leaf classes extending your custom `Returns` to implement the behaviour. When calling your custom rate limiter, you will need to make available through implicits your desired execution strategy, which can be changed per operation.

### Predefined strategies
`Strategy` is extended by three traits:
- Trait `Strategy.Blocking` gives the same return type as the operation to be executed.
- Trait `Strategy.Dropping` gives an `Option[T]` for an operation of type `=> T`.
- Trait `Strategy.BlockOrDrop` allows both types of return depending on the subclass employed.

The traits are implemented by `Strategy.Drop` and `Strategy.Block`.

### Custom strategies
Custom strategies allow not only the specification of the type, but also to add other information to the `Executor`. A possible use could be to add a timeout. The strategy could be defined like this:
```scala
type Id[A] = A
sealed trait CustomStrategy[F[*]] extends Strategy[F]
case class DropAfterTimeout(timeout: Long) extends CustomStrategy[Option]
case class RunAfterTimeout(timeout: Long) extends CustomStrategy[Id]
```

## Rate limiter algorithm
The `RateLimiterAlgorithm` employed by `RateLimiter` and `GenericRateLimiter` can be extended to implement new algorithms or modify existing ones. Its interface is modelled like that of a `Semaphore` although the underlying implementation could be different.