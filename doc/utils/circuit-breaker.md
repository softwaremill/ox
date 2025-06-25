# Circuit Breaker

A circuit breaker is used to provide stability and prevent cascading failures in distributed systems. 
These should be used with other mechanisms (such as timeouts or rate limiters) to prevent the failure of a single component from bringing down all components.
The Circuit Breaker can proactively identify unresponsive services and prevent repeated attempts.

The circuit breaker allows controlling execution of operations and stops if certain condition are met. CircuitBreaker is thread-safe and can be used in concurrent scenarios.

## API

```scala mdoc:compile-only
import ox.supervised
import ox.resilience.*

supervised:
  val circuitBreaker = CircuitBreaker()

  type T
  def operation: T = ???

  val operationResult: Option[T] = circuitBreaker.runOrDrop(operation)
```

The CircuitBreaker is a finite state machine with three states: `Closed`, `Open` and `HalfOpen`.
- While in `Open` state - all calls are dropped.
- In `Closed` state - calls are accepted.
- In `HalfOpen` state - only configured number of call can be started and depending on their results state can go back to `Open` or `Closed`. See [conditions for state change](#conditions-for-state-change).


## Configuration

Many config parameters relate to calculated metrics. Those metrics are percentage of calls that failed and percentage of calls that exceeded `slowCallDurationThreshold`. 
Which calls are included during calculation of these metrics are determined by `SlidingWindow` configuration.

### Sliding window

There are two ways that metrics are calculated.

- Count based sliding window - `SlidingWindow.CountBased`, counts metrics based on last n call results.
- Time based sliding window - `SlidingWindow.TimeBased`, counts metrics based on call results recorded in the lapse of duration before current time.

### Failure rate and slow call rate thresholds

The state of the CircuitBreaker changes from `Closed` to `Open` when the failure rate is greater or equal to configurable threshold. For example when 80% of recorded call results failed.
Failures are counted based on provided `ErrorMode`. For example any exception that is thrown by the operation, when using the direct, "unwrapped" API or any `Left` variant when using `runOrDropEither`.

The same state change also happen when percentage of slow calls (exceeding configurable `slowCallDurationThreshold`) is equal or greater than configured threshold. For example 80% of calls took longer then 10 seconds.

Those metrics are considered only when number of recorder calls is greater or equal to `minimumNumberOfCalls`, otherwise we don't change state even if failure rate is 100%.

### Parameters

- `failureRateThreshold: PercentageThreshold` - percentage of recorded calls marked as failed required to switch to open state.
- `slowCallThreshold: PercentageThreshold` - percentage of recorder calls marked as slow required to switch to open state.
- `slowCallDurationThreshold: FiniteDuration` - duration that call has to exceed to be marked as slow.
- `slidingWindow: SlidingWindow` - mechanism to determine how calls are recorded.
- `minimumNumberOfCalls: Int` - minimum number of calls recorded needed for breaker to be able to switch to open state based on thresholds.
- `waitDurationOpenState: FiniteDuration` - duration that CircuitBreaker will wait before switching from `Open` state to `HalfOpen`.
- `halfOpenTimeoutDuration: FiniteDuration` - timeout for `HalfOpen` state after which, if not enough calls were recorder, breaker will go back to `Open` state. Zero means there is no timeout.
- `numberOfCallsInHalfOpenState: Int` - number of calls recorded in `HalfOpen` state needed to calculate metrics to decide if breaker should go back to `Open` state or `Closed`. It is also maximum number of operations that can be started in this state.

`SlidingWindow` variants:

- `CountBased(windowSize: Int)` - This variant calculates metrics based on last n results of calls recorded. These statistics are cleared on every state change.
- `TimeBased(duration: FiniteDuration)` - This variant calculates metrics of operations in the lapse of `duration` before current time. These statistics are cleared on every state change.

### Providing configuration

CircuitBreaker can be configured during instantiation by providing `CircuitBreakerConfig`.

```scala mdoc:compile-only
import ox.supervised
import ox.resilience.*
import scala.concurrent.duration.*

supervised:
  // using default config
  CircuitBreaker()
  // or
  CircuitBreaker(CircuitBreakerConfig.default)
  
  // custom config
  val config = CircuitBreakerConfig(
    failureRateThreshold = PercentageThreshold(50),
    slowCallThreshold = PercentageThreshold(50),
    slowCallDurationThreshold = 10.seconds,
    slidingWindow = SlidingWindow.CountBased(100),
    minimumNumberOfCalls = 20,
    waitDurationOpenState = 10.seconds,
    halfOpenTimeoutDuration = 0.millis,
    numberOfCallsInHalfOpenState = 10
  )
  
  // providing config for CircuitBreaker instance
  CircuitBreaker(config)
```

Values defined in `CircuitBreakerConfig.default`:

```
failureRateThreshold = PercentageThreshold(50)
slowCallThreshold = PercentageThreshold(50)
slowCallDurationThreshold = 10.seconds
slidingWindow = SlidingWindow.CountBased(100)
minimumNumberOfCalls = 20
waitDurationOpenState = 10.seconds,
halfOpenTimeoutDuration = 0.millis,
numberOfCallsInHalfOpenState = 10
```

## Conditions for state change

![state diagram](/_static/state-diagram-cb.svg)

1. State changes from `Closed` to `Open` after any threshold was exceeded (`failureThreshold` or `slowThreshold`) and number of recorder calls is equal or greater than `minimumNumberOfCalls`.
2. State changes from `Closed` to `HalfOpen` if any threshold was exceeded, number of recorder calls is equal or greater than `minimumNumberOfCalls` and `waitDurationOpenState` is zero.
3. State changes from `Open` to `HalfOpen` when `waitDurationOpenState` passes.
4. State changes from `HalfOpen` to `Open` if `halfOpenTimeoutDuration` passes without enough calls recorded or number of recorder calls is equal to `numberOfCallsInHalfOpenState` and any threshold was exceeded.
5. State changes from `HalfOpen` to `Closed` if `numberOfCallsInHalfOpenState` where completed before timeout and there wasn't any threshold exceeded.


```{note}
CircuitBreaker uses actor internally and since actor executes on one thread this may be bottleneck. That means that calculating state change can be delayed and breaker can let few more operations to complete before opening.
This can be the case with many very fast operations.
```

## Examples

```scala mdoc:compile-only
import ox.UnionMode
import ox.resilience.*
import ox.scheduling.Schedule
import ox.supervised
import scala.concurrent.duration.*

def directOperation: Int = ???
def eitherOperation: Either[String, Int] = ???
def unionOperation: String | Int = ???

supervised:
  val circuitBreaker = CircuitBreaker()

  // various operation definitions
  circuitBreaker.runOrDrop(directOperation)
  circuitBreaker.runOrDropEither(eitherOperation)

  // custom error mode
  circuitBreaker.runOrDropWithErrorMode(UnionMode[String])(unionOperation)

  // retry with circuit breaker inside
  retryEither(Schedule.exponentialBackoff(100.millis).maxRetries(3)){
    circuitBreaker.runOrDrop(directOperation) match
      case Some(value) => Right(value)
      case None => Left("Operation dropped")
  }
```
