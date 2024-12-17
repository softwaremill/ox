# Rate limiter

The rate limiter mechanism allows controlling the rate at which operations are executed. It ensures that at most a certain number of operations are run concurrently within a specified time frame, preventing system overload and ensuring fair resource usage. 

Several rate limiting algorithms are available, either taking into account only the start time of the operation, or the entire duration of its execution.

## API

Basic rate limiter usage:

```scala
import ox.supervised
import ox.resilience.*
import scala.concurrent.duration.*

supervised: 
  val rateLimiter = RateLimiter.fixedWindowWithStartTime(2, 1.second)

  type T
  def operation: T = ???

  val blockedOperation: T = rateLimiter.runBlocking(operation)
  val droppedOperation: Option[T] = rateLimiter.runOrDrop(operation)
```

`blockedOperation` will block the operation until the algorithm allows it to be executed. Therefore, the return type is the same as the operation. On the other hand, if the algorithm doesn't allow execution of more operations, `runOrDrop` will drop the operation returning `None` and wrapping the result in `Some` when the operation is successfully executed.

A rate limiter must be created within an `Ox` [concurrency scope](../structured-concurrency/fork-join.md), as a background fork is created, to replenish the rate limiter. Once the scope ends, the rate limiter is stops as well.

## Operation definition

The `operation` can be provided directly using a by-name parameter, i.e. `f: => T`.

## Configuration

The configuration of a `RateLimiter` depends on an underlying algorithm that controls whether an operation can be executed or not. The following algorithms are available:
- `StartTimeRateLimiterAlgorithm.FixedWindow(rate: Int, per: FiniteDuration)` - where `rate` is the maximum number of operations to be executed in fixed windows of `per` duration.
- `StartTimeRateLimiterAlgorithm.SlidingWindow(rate: Int, per: FiniteDuration)` - where `rate` is the maximum number of operations to be executed in any window of time of duration `per`.
- `StartTimeRateLimiterAlgorithm.LeakyBucket(maximum: Int, per: FiniteDuration)` - where `rate` is the maximum capacity of tokens available in the token bucket algorithm and one token is added each `per`. It can represent both the leaky bucket algorithm or the token bucket algorithm.
- `DurationRateLimiterAlgorithm.FixedWindow(rate: Int, per: FiniteDuration)` - where `rate` is the maximum number of operations which execution spans fixed windows of `per` duration. Considers whole execution time of an operation. Operation spanning more than one window blocks permits in all windows that it spans.
- `DurationRateLimiterAlgorithm.SlidingWindow(rate: Int, per: FiniteDuration)` - where `rate` is the maximum number of operations which execution spans any window of time of duration `per`. Considers whole execution time of an operation. Operation release permit after `per` passed since operation ended.

### API shorthands

You can use one of the following shorthands to define a Rate Limiter with the corresponding algorithm:

- `RateLimiter.fixedWindowWithStartTime(maxOperations: Int, window: FiniteDuration)`
- `RateLimiter.slidingWindowWithStartTime(maxOperations: Int, window: FiniteDuration)`
- `RateLimiter.leakyBucket(maxTokens: Int, refillInterval: FiniteDuration)`
- `RateLimiter.fixedWindowWithDuration(maxOperations: Int, window: FiniteDuration)`
- `RateLimiter.slidingWindowWithDuration(maxOperations: Int, window: FiniteDuration)`

See the tests in `ox.resilience.*` for more.

## Custom rate limiter algorithms

The `RateLimiterAlgorithm` employed by `RateLimiter` can be extended to implement new algorithms or modify existing ones. Its interface is modelled like that of a `Semaphore` although the underlying implementation could be different. For best compatibility with the existing interface of `RateLimiter`, methods `acquire` and `tryAcquire` should offer the same guaranties as Java's `Semaphores`. There is also method `def runOperation[T](operation: => T, permits: Int): T` for cases where considering span of execution may be necessary (see implementations in `DurationRateLimiterAlgorithm`).

Additionally, there are two methods employed by the `RateLimiter` for updating its internal state automatically:
- `def update(): Unit`: Updates the internal state of the rate limiter to reflect its current situation. Invoked in a background fork repeatedly, when a rate limiter is created.
- `def getNextUpdate: Long`: Returns the time in nanoseconds after which a new `update` needs to be called.
