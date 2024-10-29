# Rate limiter
The rate limiter mechanism allows controlling the rate at which operations are executed. It ensures that a certain number of operations are performed within a specified time frame, preventing system overload and ensuring fair resource usage. 

## API

The basic syntax for rate limiters is:

```scala
import ox.resilience.*

val algorithm = RateLimiterAlgorithm.FixedRate(2, FiniteDurationt(1, "seconds"))
val rateLimiter = RateLimiter(algorithm)

type T
def operation: T = ???

val blockedOperation: T = rateLimiter.runBlocking(operation)
val droppedOperation: Some[T] = rateLimiter.runOrDrop(operation)
```

`blockedOperation` will block the operation until the algorithm allows it to be executed. Therefore, the return type is the same as the operation. On the other hand, if the algorithm doesn't allow execution of more operations, `runOrDrop` will drop the operation returning `None` and wrapping the result in `Some` when the operation is successfully executed.
The `RateLimiter` API uses the `GenericRateLimiter` API underneath. See [custom rate limiters](custom-rate-limiter.md) for more details.

## Operation definition

The `operation` can be provided directly using a by-name parameter, i.e. `f: => T`.

## Configuration

The configuration of a `RateLimiter` depends on an underlying algorithm that controls whether an operation can be executed or not. The following algorithms are available:
- `RateLimiterAlgorithm.FixedRate(rate: Int, dur: FiniteDuration)` - where `rate` is the maximum number of operations to be executed in segments of `dur` duration after the execution of the first operation.
- `RateLimiterAlgorithm.SlidingWindow(rate: Int, dur: FiniteDuration)` - where `rate` is the maximum number of operations to be executed in the a windows of time of duration `dur`.
- `RateLimiterAlgorithm.TokenBucket(maximum: Int, dur: FiniteDuration)` - where `maximum` is the maximum capacity of tokens availables in the token bucket algorithm and one token is added after `dur`.
- `RateLimiterAlgorithm.LeakyBucket(maximum: Int, dur: FiniteDuration)` - where `maximum` is the maximum capacity availables in the leaky bucket algorithm and 0 capacity is achieved after `dur` duration.

It's possible to define your own algorithm. See [custom rate limiters](custom-rate-limiter.md) for more details.
### API shorthands

You can use one of the following shorthands to define a Rate Limiter with the corresponding algorithm:

- `RateLimiter.fixedRate(rate: Int, dur: FiniteDuration)`,
- `RateLimiter.slidingWindow(rate: Int, dur: FiniteDuration)`,
- `RateLimiter.tokenBucket(maximum: Int, dur: FiniteDuration)`,
- `RateLimiter.leakyBucket(maximum: Int, dur: FiniteDuration)`.

See the tests in `ox.resilience.*` for more.
