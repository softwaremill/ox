# Retries

The retries mechanism allows to retry a failing operation according to a given policy (e.g. retry 3 times with a 100ms
delay between attempts).

## API

The basic syntax for retries is:

```scala
import ox.retry.retry

retry(operation, onRetry)(policy)
```

or, using syntax sugar:

```scala
import ox.syntax.*

operation.retry(policy, onRetry)
```

## Operation definition

The `operation` can be provided directly using a by-name parameter, i.e. `f: => T`.

There's also a `retryEither` variant which accepts a by-name `Either[E, T]`, i.e. `f: => Either[E, T]`, as well as one
which accepts arbitrary [error modes](error-handling.md), accepting the computation in an `F` context: `f: => F[T]`.

## OnRetry definition

The `onRetry` callback is a function that is invoked after each attempt to execute the operation. It is used to perform
any necessary actions or checks after each attempt, regardless of whether the attempt was successful or not.

The callback function has the following signature:

```
(Int, Either[E, T]) => Unit
```

Where:

- The first parameter, an `Int`, represents the attempt number of the retry operation.
- The second parameter is an `Either[E, T]` type, representing the result of the retry operation. Left represents an
  error
  and Right represents a successful result.

## Policies

A retry policy consists of two parts:

- a `Schedule`, which indicates how many times and with what delay should we retry the `operation` after an initial
  failure,
- a `ResultPolicy`, which indicates whether:
    - a non-erroneous outcome of the `operation` should be considered a success (if not, the `operation` would be
      retried),
    - an erroneous outcome of the `operation` should be retried or fail fast.

The available schedules are defined in the `Schedule` object. Each schedule has a finite and an infinite variant.

### Finite schedules

Finite schedules have a common `maxRetries: Int` parameter, which determines how many times the `operation` would be
retried after an initial failure. This means that the operation could be executed at most `maxRetries + 1` times.

### Infinite schedules

Each finite schedule has an infinite variant, whose settings are similar to those of the respective finite schedule, but
without the `maxRetries` setting. Using the infinite variant can lead to a possibly infinite number of retries (unless
the `operation` starts to succeed again at some point). The infinite schedules are created by calling `.forever` on the
companion object of the respective finite schedule (see examples below).

### Schedule types

The supported schedules (specifically - their finite variants) are:

- `Immediate(maxRetries: Int)` - retries up to `maxRetries` times without any delay between subsequent attempts.
- `Delay(maxRetries: Int, delay: FiniteDuration)` - retries up to `maxRetries` times , sleeping for `delay` between
  subsequent attempts.
- `Backoff(maxRetries: Int, initialDelay: FiniteDuration, maxDelay: FiniteDuration, jitter: Jitter)` - retries up
  to `maxRetries` times , sleeping for `initialDelay` before the first retry, increasing the sleep between subsequent
  attempts exponentially (with base `2`) up to an optional `maxDelay` (default: 1 minute).

  Optionally, a random factor (jitter) can be used when calculating the delay before the next attempt. The purpose of
  jitter is to avoid clustering of subsequent retries, i.e. to reduce the number of clients calling a service exactly at
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

### API shorthands

When you don't need to customize the result policy (i.e. use the default one), you can use one of the following
shorthands to define a retry policy with a given schedule (note that the parameters are the same as when manually
creating the respective `Schedule`):

- `RetryPolicy.immediate(maxRetries: Int)`,
- `RetryPolicy.immediateForever`,
- `RetryPolicy.delay(maxRetries: Int, delay: FiniteDuration)`,
- `RetryPolicy.delayForever(delay: FiniteDuration)`,
- `RetryPolicy.backoff(maxRetries: Int, initialDelay: FiniteDuration, maxDelay: FiniteDuration, jitter: Jitter)`,
- `RetryPolicy.backoffForever(initialDelay: FiniteDuration, maxDelay: FiniteDuration, jitter: Jitter)`.

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
import ox.retry.{retry, retryEither, retryWithErrorMode}
import ox.retry.{Jitter, ResultPolicy, RetryPolicy, Schedule}
import scala.concurrent.duration.*

def directOperation: Int = ???
def eitherOperation: Either[String, Int] = ???
def unionOperation: String | Int = ???

// various operation definitions - same syntax
retry(directOperation)(RetryPolicy.immediate(3))
retryEither(eitherOperation)(RetryPolicy.immediate(3))

// various policies with custom schedules and default ResultPolicy
retry(directOperation)(RetryPolicy.delay(3, 100.millis))
retry(directOperation)(RetryPolicy.backoff(3, 100.millis)) // defaults: maxDelay = 1.minute, jitter = Jitter.None
retry(directOperation)(RetryPolicy.backoff(3, 100.millis, 5.minutes, Jitter.Equal))

// infinite retries with a default ResultPolicy
retry(directOperation)(RetryPolicy.delayForever(100.millis))
retry(directOperation)(RetryPolicy.backoffForever(100.millis, 5.minutes, Jitter.Full))

// result policies
// custom success
retry(directOperation)(RetryPolicy(Schedule.Immediate(3), ResultPolicy.successfulWhen(_ > 0)))
// fail fast on certain errors
retry(directOperation)(RetryPolicy(Schedule.Immediate(3), ResultPolicy.retryWhen(_.getMessage != "fatal error")))
retryEither(eitherOperation)(RetryPolicy(Schedule.Immediate(3), ResultPolicy.retryWhen(_ != "fatal error")))

// custom error mode
retryWithErrorMode(UnionMode[String])(unionOperation)(RetryPolicy(Schedule.Immediate(3), ResultPolicy.retryWhen(_ != "fatal error")))
```

See the tests in `ox.retry.*` for more.
