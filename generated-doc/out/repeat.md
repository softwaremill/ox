# Repeat

The `repeat` functions allow to repeat an operation according to a given schedule (e.g. repeat 3 times with a 100ms
interval and 50ms of initial delay).

## API

The basic syntax for `repeat` is:

```scala
import ox.scheduling.repeat

repeat(config)(operation)
```

The `repeat` API uses `scheduled` underneath with DSL focused on repeats. See [scheduled](scheduled.md) for more details.

## Operation definition

Similarly to the `retry` API, the `operation` can be defined: 
* directly using a by-name parameter, i.e. `f: => T`
* using a by-name `Either[E, T]`
* or using an arbitrary [error mode](basics/error-handling.md), accepting the computation in an `F` context: `f: => F[T]`.

## Configuration

The `repeat` config requires a `Schedule`, which indicates how many times and with what interval should the `operation` 
be repeated.

In addition, it is possible to define a custom `shouldContinueOnSuccess` strategy for deciding if the operation
should continue to be repeated after a successful result returned by the previous operation (defaults to `_: T => true`).

If an operation returns an error, the repeat loop will always be stopped. If an error handling within the operation
is needed, you can use a `retry` inside it (see an example below) or use `scheduled` instead of `repeat`, which allows
full customization.

### API shorthands

You can use one of the following shorthands to define a `RepeatConfig` with a given schedule with an optional initial delay:
- `RepeatConfig.immediate(maxInvocations: Int, initialDelay: Option[FiniteDuration] = None)`
- `RepeatConfig.immediateForever[E, T](initialDelay: Option[FiniteDuration] = None)`
- `RepeatConfig.fixedRate[E, T](maxInvocations: Int, interval: FiniteDuration, initialDelay: Option[FiniteDuration] = None)`
- `RepeatConfig.fixedRateForever[E, T](interval: FiniteDuration, initialDelay: Option[FiniteDuration] = None)`
- `RepeatConfig.backoff[E, T](maxInvocations: Int, firstInterval: FiniteDuration, maxInterval: FiniteDuration = 1.minute, jitter: Jitter = Jitter.None, initialDelay: Option[FiniteDuration] = None)`
- `RepeatConfig.backoffForever[E, T](firstInterval: FiniteDuration, maxInterval: FiniteDuration = 1.minute, jitter: Jitter = Jitter.None, initialDelay: Option[FiniteDuration] = None)`

See [scheduled](scheduled.md) for details on how to create custom schedules.

## Examples

```scala
import ox.UnionMode
import ox.scheduling.{Jitter, Schedule, repeat, repeatEither, repeatWithErrorMode, RepeatConfig}
import ox.resilience.{retry, RetryConfig}
import scala.concurrent.duration.*

def directOperation: Int = ???
def eitherOperation: Either[String, Int] = ???
def unionOperation: String | Int = ???

// various operation definitions - same syntax
repeat(RepeatConfig.immediate(3))(directOperation)
repeatEither(RepeatConfig.immediate(3))(eitherOperation)

// various schedules
repeat(RepeatConfig.fixedRate(3, 100.millis))(directOperation)
repeat(RepeatConfig.fixedRate(3, 100.millis, Some(50.millis)))(directOperation)

// infinite repeats with a custom strategy
def customStopStrategy: Int => Boolean = ???
repeat(RepeatConfig.fixedRateForever(100.millis).copy(shouldContinueOnResult = customStopStrategy))(directOperation)

// custom error mode
repeatWithErrorMode(UnionMode[String])(RepeatConfig.fixedRate(3, 100.millis))(unionOperation)

// repeat with retry inside
repeat(RepeatConfig.fixedRate(3, 100.millis)) {
  retry(RetryConfig.backoff(3, 100.millis))(directOperation)
}
```
