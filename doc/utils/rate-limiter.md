# Rate limiter
The rate limiter mechanism allows controlling the rate at which operations are executed. It ensures that a certain number of operations are performed within a specified time frame, preventing system overload and ensuring fair resource usage. Note that the implemented limiting mechanism within `Ox` only take into account the start of execution and not the whole execution of an operation. This could be tweaked customizing the rate limiter algorithm employed or the interface of rate limiter. 

## API

The basic syntax for rate limiters is:

```scala mdoc:compile-only
import ox.supervised
import ox.resilience.*
import scala.concurrent.duration.*

val algorithm = RateLimiterAlgorithm.FixedWindow(2, 1.second)

supervised: 
  val rateLimiter = RateLimiter(algorithm)

  type T
  def operation: T = ???

  val blockedOperation: T = rateLimiter.runBlocking(operation)
  val droppedOperation: Option[T] = rateLimiter.runOrDrop(operation)
```

`blockedOperation` will block the operation until the algorithm allows it to be executed. Therefore, the return type is the same as the operation. On the other hand, if the algorithm doesn't allow execution of more operations, `runOrDrop` will drop the operation returning `None` and wrapping the result in `Some` when the operation is successfully executed.

## Operation definition

The `operation` can be provided directly using a by-name parameter, i.e. `f: => T`.

## Configuration

The configuration of a `RateLimiter` depends on an underlying algorithm that controls whether an operation can be executed or not. The following algorithms are available:
- `RateLimiterAlgorithm.FixedWindow(rate: Int, dur: FiniteDuration)` - where `rate` is the maximum number of operations to be executed in segments of `dur` duration after the execution of the first operation.
- `RateLimiterAlgorithm.SlidingWindow(rate: Int, dur: FiniteDuration)` - where `rate` is the maximum number of operations to be executed in the a windows of time of duration `dur`.
- `RateLimiterAlgorithm.Bucket(maximum: Int, dur: FiniteDuration)` - where `maximum` is the maximum capacity of tokens availables in the token bucket algorithm and one token is added after `dur`. It can represent both the leaky bucket algorithm or the tocken bucket algorithm.

### API shorthands

You can use one of the following shorthands to define a Rate Limiter with the corresponding algorithm:

- `RateLimiter.FixedWindow(rate: Int, dur: FiniteDuration)`,
- `RateLimiter.slidingWindow(rate: Int, dur: FiniteDuration)`,
- `RateLimiter.bucket(maximum: Int, dur: FiniteDuration)`,

See the tests in `ox.resilience.*` for more.

## Custom rate limiter algorithms
The `RateLimiterAlgorithm` employed by `RateLimiter` and `GenericRateLimiter` can be extended to implement new algorithms or modify existing ones. Its interface is modelled like that of a `Semaphore` although the underlying implementation could be different. For best compatibility with the existing interface of `RateLimiter`, methods `acquire` and `tryAcquire` should offer the same guaranties as Java `Semaphores`.

Additionally, there are two methods employed by the `GenericRateLimiter` for updating its internal state automatically:
- `def update: Unit`: Updates the internal state of the rate limiter to reflect its current situation.
- `def getNextUpdate: Long`: Returns the time in nanoseconds after which a new `update` needs to be called.
