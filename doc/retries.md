# Retries

The retries mechanism allows to retry a failing operation according to a given configuration (e.g. retry 3 times with a 100ms
delay between attempts).

## API

The basic syntax for retries is:

```scala
import ox.resilience.retry

retry(config)(operation)
```

The `retry` API uses `scheduled` underneath with DSL focused on retries. See [scheduled](scheduled.md) for more details.

## Operation definition

The `operation` can be provided directly using a by-name parameter, i.e. `f: => T`.

There's also a `retryEither` variant which accepts a by-name `Either[E, T]`, i.e. `f: => Either[E, T]`, as well as one
which accepts arbitrary [error modes](basics/error-handling.md), accepting the computation in an `F` context: `f: => F[T]`.

## Configuration

A retry config consists of three parts:

- a `Schedule`, which indicates how many times and with what delay should we retry the `operation` after an initial
  failure,
- a `ResultPolicy`, which indicates whether:
    - a non-erroneous outcome of the `operation` should be considered a success (if not, the `operation` would be
      retried),
    - an erroneous outcome of the `operation` should be retried or fail fast.
- a `onRetry`, which is a callback function that is invoked after each attempt to execute the operation. It is used to
  perform any necessary actions or checks after each attempt, regardless of whether the attempt was successful or not.

The available schedules are defined in the `Schedule` object. Each schedule has a finite and an infinite variant.
See [scheduled](scheduled.md) for more details.

### Result policies

A result policy allows to customize how the results of the `operation` are treated. It consists of two predicates:

- `isSuccess: T => Boolean` (default: `true`) - determines whether a non-erroneous result of the `operation` should be
  considered a success. When it evaluates to `true` - no further attempts would be made, otherwise - we'd keep retrying.

  With finite schedules (i.e. those with `maxRetries` defined), if `isSuccess` keeps returning `false` when `maxRetries`
  are reached, the result is returned as-is, even though it's considered "unsuccessful",
- `isWorthRetrying: E => Boolean` (default: `true`) - determines whether another attempt would be made if
  the `operation` results in an error `E`. When it evaluates to `true` - we'd keep retrying, otherwise - we'd fail fast
  with the error.

The `ResultPolicy[E, T]` is generic both over the error (`E`) and result (`T`) type. Note, however, that for the direct
variant `retry`, the error type `E` is fixed to `Throwable`, while for the `Either` and error-mode variants, `E` can ba
an arbitrary type.

### On retry

The callback function has the following signature:

```
(Int, Either[E, T]) => Unit
```

Where:
- The first parameter, an `Int`, represents the attempt number of the retry operation.
- The second parameter is an `Either[E, T]` type, representing the result of the retry operation. Left represents an
  error and Right represents a successful result.

### API shorthands

When you don't need to customize the result policy (i.e. use the default one), you can use one of the following
shorthands to define a retry config with a given schedule (note that the parameters are the same as when manually
creating the respective `Schedule`):

- `RetryConfig.immediate(maxRetries: Int)`,
- `RetryConfig.immediateForever`,
- `RetryConfig.delay(maxRetries: Int, delay: FiniteDuration)`,
- `RetryConfig.delayForever(delay: FiniteDuration)`,
- `RetryConfig.backoff(maxRetries: Int, initialDelay: FiniteDuration, maxDelay: FiniteDuration, jitter: Jitter)`,
- `RetryConfig.backoffForever(initialDelay: FiniteDuration, maxDelay: FiniteDuration, jitter: Jitter)`.

If you want to customize a part of the result policy, you can use the following shorthands:

- `ResultPolicy.default[E, T]` - uses the default settings,
- `ResultPolicy.successfulWhen[E, T](isSuccess: T => Boolean)` - uses the default `isWorthRetrying` and the
  provided `isSuccess`,
- `ResultPolicy.retryWhen[E, T](isWorthRetrying: E => Boolean)` - uses the default `isSuccess` and the
  provided `isWorthRetrying`,
- `ResultPolicy.neverRetry[E, T]` - uses the default `isSuccess` and fails fast on any error.

## Examples

```scala mdoc:compile-only
import ox.UnionMode
import ox.resilience.{retry, retryEither, retryWithErrorMode, ResultPolicy, RetryConfig}
import ox.scheduling.{Jitter, Schedule}
import scala.concurrent.duration.*

def directOperation: Int = ???
def eitherOperation: Either[String, Int] = ???
def unionOperation: String | Int = ???

// various operation definitions - same syntax
retry(RetryConfig.immediate(3))(directOperation)
retryEither(RetryConfig.immediate(3))(eitherOperation)

// various configs with custom schedules and default ResultPolicy
retry(RetryConfig.delay(3, 100.millis))(directOperation)
retry(RetryConfig.backoff(3, 100.millis))(directOperation) // defaults: maxDelay = 1.minute, jitter = Jitter.None
retry(RetryConfig.backoff(3, 100.millis, 5.minutes, Jitter.Equal))(directOperation)

// infinite retries with a default ResultPolicy
retry(RetryConfig.delayForever(100.millis))(directOperation)
retry(RetryConfig.backoffForever(100.millis, 5.minutes, Jitter.Full))(directOperation)

// result policies
// custom success
retry[Int](RetryConfig(Schedule.Immediate(3), ResultPolicy.successfulWhen(_ > 0)))(directOperation)
// fail fast on certain errors
retry(RetryConfig(Schedule.Immediate(3), ResultPolicy.retryWhen(_.getMessage != "fatal error")))(directOperation)
retryEither(RetryConfig(Schedule.Immediate(3), ResultPolicy.retryWhen(_ != "fatal error")))(eitherOperation)

// custom error mode
retryWithErrorMode(UnionMode[String])(RetryConfig(Schedule.Immediate(3), ResultPolicy.retryWhen(_ != "fatal error")))(unionOperation)
```

See the tests in `ox.resilience.*` for more.
