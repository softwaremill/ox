# Circuit Breaker

The circuit breaker allows controlling execution of operations and stops if certain condition are met. CircuitBreaker is thread-safe and uses [actor](./actors.md) underneath to change breaker state.

```{note}
Since actor executes on one thread which may be bottleneck. That means that calculating state change can be deleyad and breaker can let few more operations to complete before openning.
This can be the case with many very fast operations.
```

## API

```scala mdoc:compile-only
import ox.supervised
import ox.resilience.*

supervised:
  val circuitBreaker = CircuitBreaker(CircuitBreakerConfig.default)

  type T
  def operation: T = ???

  val operationResult: Option[T] = circuitBreaker.runOrDrop(operation)
```

## Configuration

### Sliding window

There are two ways that metrics are calculated.

- Count based sliding window - `SlidingWindow.CountBased`, counts metrics based on last n call results.
- Time based sliding window - `SlidingWindow.TimeBased`, counts metrics based on call results recorded in the lapse of duration before current time.

### Failure rate and slow call rate thresholds

The state of the CircuitBreaker changes from `Closed` to `Open` when the `failureRate` is greater or equal to configurable threshold. For example when 80% of recorded call results failed.
Failures are counted based on provided `ErrorMode`.

The same state change also happen when percentage of slow calls (exceeding `slowCallDurationThreshold`) is equal or greater than configured threshold. For exmaple 80% of calls took longer then 10 seconds.

Those metrics are considered only when number of recorder calls is greater or equal to `minimumNumberOfCalls`, otherwise we don't change state even if `failureRate` is 100%.

### Parameters

- `failureRateThreshold: PercentageThreshold` - percentage of recorder calls marked as failed required to switch to open state
- `slowCallThreshold: PercentageThreshold` - percentage of recorder calls marked as slow required to switch to open state
- `slowCallDurationThreshold: FiniteDuration` - duration that call has to exceed to be marked as slow
- `slidingWindow: SlidingWindow` - mechanism to determine how many calls are recorded
- `minimumNumberOfCalls: Int` - minium number of calls recored for breaker to be able to swtich to open state based on thresholds
- `waitDurationOpenState: FiniteDuration` - duration that CircuitBreaker will wait before switching from `Open` state to `HalfOpen`
- `halfOpenTimeoutDuration: FiniteDuration` - timeout for `HalfOpen` state after which, if not enough calls were recorder, breaker will go back to `Open` state
- `numberOfCallsInHalfOpenState: Int` - number of calls recorded in `HalfOpen` state needed to calculate metrics to decide if breaker should go back to `Open` state or `Closed`

Values defined in `CircuitBreakerConfig.default`:
```
failureRateThreshold = PercentageThreshold(50)
slowCallThreshold = PercentageThreshold(50)
slowCallDurationThreshold = 60.seconds
slidingWindow = SlidingWindow.CountBased(100)
minimumNumberOfCalls = 20
waitDurationOpenState = FiniteDuration(10, TimeUnit.SECONDS)
halfOpenTimeoutDuration = FiniteDuration(0, TimeUnit.MILLISECONDS)
numberOfCallsInHalfOpenState = 10
```

## Conditions for state change

![state diagram](/_static/state-diagram-cb.svg)


1. State changes from `Closed` to `Open` after any threshold was exceeded (failureThreshold or slowThreshold) and number of recorder calls is equal or greater than minimumNumberOfCalls.
2. State changes from `Closed` to `HalfOpen` if any threshold was exceeded, number of recorder calls is equal or greater than `minimumNumberOfCalls` and `waitDurationOpenState` is zero.
3. State changes from `Open` to `HalfOpen` when `waitDurationOpenState` passes.
4. State changes from `HalfOpen` to `Open` if `halfOpenTimeoutDuration` passes without enough calls recorded or number of recorder calls is equal to `numberOfCallsInHalfOpenState` and any threshold was exceeded.
5. State changes from `HalfOpen` to `Closed` if `numberOfCallsInHalfOpenState` where completed before timeout and there wasn't any threshold exceeded.


## Examples

```scala mdoc:compile-only
import ox.UnionMode
import ox.supervised
import ox.resilience.*
import scala.concurrent.duration.*

def directOperation: Int = ???
def eitherOperation: Either[String, Int] = ???
def unionOperation: String | Int = ???

supervised:
  val ciruictBreaker = CircuitBreaker(CircuitBreakerConfig.default)

  // various operation definitions
  ciruictBreaker.runOrDrop(directOperation)
  ciruictBreaker.runOrDropEither(eitherOperation)

  // custom error mode
  ciruictBreaker.runOrDropWithErrorMode(UnionMode[String])(unionOperation)

  // retry with circuit breaker inside
  retry(RetryConfig.backoff(3, 100.millis)){
    ciruictBreaker.runOrDrop(directOperation).get
  }
```
