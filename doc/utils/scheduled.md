# Scheduled

The `scheduled` functions allow to run an operation according to a given schedule.
It is preferred to use `repeat`, `retry`, or combination of both functions for most use cases, as they provide a more convenient DSL.
In fact `retry` and `repeat` use `scheduled` internally.

## Operation definition

Similarly to the `retry` and `repeat` APIs, the `operation` can be defined: 
* directly using a by-name parameter, i.e. `f: => T`
* using a by-name `Either[E, T]`
* or using an arbitrary [error mode](../basics/error-handling.md), accepting the computation in an `F` context: `f: => F[T]`.

## Configuration

The `scheduled` config consists of:
- a `Schedule`, which indicates how many times the `operation` should be run, provides a duration based on which
  a sleep is calculated and provides an initial delay if configured.
- a `SleepMode`, which determines how the sleep between subsequent operations should be calculated:
  - `Interval` - default for `repeat` operations, where the sleep is calculated as the duration provided by schedule 
    minus the duration of the last operation (can be negative, in which case the next operation occurs immediately).
  - `Delay` - default for `retry` operations, where the sleep is just the duration provided by schedule.
- `onOperationResult` - a callback function that is invoked after each operation. Used primarily for `onRetry` in `retry` API.

In addition, it is possible to define strategies for handling the results and errors returned by the `operation`:
- `shouldContinueOnError` - defaults to `_: E => false`, which allows to decide if the scheduler loop should continue 
  after an error returned by the previous operation.
- `shouldContinueOnSuccess` - defaults to `_: T => true`, which allows to decide if the scheduler loop should continue
  after a successful result returned by the previous operation.

## Schedule

### Finite schedules

Finite schedules have a common `maxRepeats: Int` parameter, which determines how many times the `operation` can be
repeated. This means that the operation could be executed at most `maxRepeats + 1` times.

### Infinite schedules

Each finite schedule has an infinite variant, whose settings are similar to those of the respective finite schedule, but
without the `maxRepeats` setting. Using the infinite variant can lead to a possibly infinite number of retries (unless
the `operation` starts to succeed again at some point). The infinite schedules are created by calling `.forever` on the
companion object of the respective finite schedule (see examples below).

### Schedule types

The supported schedules (specifically - their finite variants) are:

- `InitialDelay(delay: FiniteDuration)` - used to configure the initial delay (the delay before the first invocation of 
  the operation, used in `repeat` API only).
- `Immediate(maxRepeats: Int)` - repeats up to `maxRepeats` times, always returning duration equal to 0.
- `Fixed(maxRepeats: Int, duration: FiniteDuration)` - repeats up to `maxRepeats` times, always returning 
  the provided `duration`.
- `Backoff(maxRepeats: Int, firstDuration: FiniteDuration, maxDuration: FiniteDuration, jitter: Jitter)` - repeats up
  to `maxRepeats` times, returning `firstDuration` after the first invocation, increasing the duration between subsequent
  invocations exponentially (with base `2`) up to an optional `maxDuration` (default: 1 minute).

  Optionally, a random factor (jitter) can be used when calculating the delay before the next invocation. The purpose of
  jitter is to avoid clustering of subsequent invocations, i.e. to reduce the number of clients calling a service exactly at
  the same time, which can result in subsequent failures, contrary to what you would expect from retrying. By
  introducing randomness to the delays, the retries become more evenly distributed over time.

  See
  the [AWS Architecture Blog article on backoff and jitter](https://aws.amazon.com/blogs/architecture/exponential-backoff-and-jitter/)
  for a more in-depth explanation.

  The following jitter strategies are available (defined in the `Jitter` enum):
  - `None` - the default one, when no randomness is added, i.e. a pure exponential backoff is used,
  - `Full` - picks a random value between `0` and the exponential backoff calculated for the current attempt,
  - `Equal` - similar to `Full`, but prevents very short delays by always using a half of the original backoff and
    adding a random value between `0` and the other half,
  - `Decorrelated` - uses the delay from the previous attempt (`lastDelay`) and picks a random value between
    the `initalAttempt` and `3 * lastDelay`.

## Combining schedules

It is possible to combine schedules using `.andThen` method. The left side schedule must be a `Finite`
or `InitialDelay` schedule and the right side can be any schedule.

### Examples

```scala mdoc:compile-only
import ox.scheduling.Schedule
import scala.concurrent.duration.*

// schedule 3 times immediately and then 3 times with fixed duration
Schedule.Immediate(3).andThen(Schedule.Fixed(3, 100.millis))

// schedule 3 times immediately and then forever with fixed duration
Schedule.Immediate(3).andThen(Schedule.Fixed.forever(100.millis))

// schedule with an initial delay and then forever with fixed duration
Schedule.InitialDelay(100.millis).andThen(Schedule.Fixed.forever(100.millis))
```
