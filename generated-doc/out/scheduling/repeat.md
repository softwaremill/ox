# Repeat

The `repeat` functions allow to repeat an operation according to a given schedule (e.g. repeat 3 times with a 100ms
interval and 50ms of initial delay).

## API

The basic syntax for `repeat` is:

```scala
import ox.scheduling.repeat

repeat(schedule)(operation)
```

The `repeat` API uses `scheduled` underneath with DSL focused on repeats. See [scheduled](scheduled.md) for more details.

## Operation definition

Similarly to the [retry](retries.md) API, the `operation` can be defined: 

* directly using a by-name parameter, i.e. `f: => T`
* using a by-name `Either[E, T]`
* or using an arbitrary [error mode](../basics/error-handling.md), accepting the computation in an `F` context: `f: => F[T]`.

## Configuration

The `repeat` config requires a `Schedule`, which indicates how many times and with what interval should the `operation` 
be repeated.

In addition, it is possible to define a custom `shouldContinueOnResult` strategy for deciding if the operation
should continue to be repeated after a successful result returned by the previous operation (defaults to `_: T => true`).

If an operation returns an error, the repeat loop will always be stopped. If an error handling within the operation
is needed, you can use a `retry` inside it (see an example below) or use `scheduled` instead of `repeat`, which allows
full customization.

This is captured as a `RepeatConfig` instance. For convenience, the `repeat` method accepts either a full `RepeatConfig`, 
or just the `Schedule`. In the latter case, a default `RepeatConfig` is created, using the schedule.

The [retry](retries.md) documentation includes an overview of the available ways to create and modify a `Schedule`.

## Examples

```scala
import ox.UnionMode
import ox.scheduling.{Schedule, repeat, repeatEither, repeatWithErrorMode, RepeatConfig}
import ox.resilience.{retry, RetryConfig}
import scala.concurrent.duration.*

def directOperation: Int = ???
def eitherOperation: Either[String, Int] = ???
def unionOperation: String | Int = ???

// various operation definitions - same syntax
repeat(Schedule.immediate.maxAttempts(3))(directOperation)
repeatEither(Schedule.immediate.maxAttempts(3))(eitherOperation)

// various schedules
repeat(Schedule.fixedInterval(100.millis).maxAttempts(3))(directOperation)
repeat(Schedule.fixedInterval(100.millis).maxAttempts(3).withInitialDelay(50.millis))(
  directOperation)

// infinite repeats with a custom strategy
def customStopStrategy: Int => Boolean = ???
repeat(RepeatConfig(Schedule.fixedInterval(100.millis))
  .copy(shouldContinueOnResult = customStopStrategy))(directOperation)

// custom error mode
repeatWithErrorMode(UnionMode[String])(Schedule.fixedInterval(100.millis).maxAttempts(3))(
  unionOperation)

// repeat with retry inside
repeat(Schedule.fixedInterval(100.millis).maxAttempts(3)) {
  retry(Schedule.exponentialBackoff(100.millis).maxAttempts(3))(directOperation)
}
```
