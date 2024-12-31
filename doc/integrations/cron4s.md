# Cron scheduler

Dependency:

```scala
"com.softwaremill.ox" %% "cron" % "@VERSION@"
```

This module allows to run schedules based on cron expressions from [cron4s](https://github.com/alonsodomin/cron4s).

`CronSchedule` can be used in all places that requires `Schedule` especially in repeat scenarios.

For defining `CronExpr` see [cron4s documentation](https://www.alonsodomin.me/cron4s/userguide/index.html).

## Api

The basic syntax for `cron.repeat` is similar to `repeat`:

```scala
import ox.scheduling.cron.repeat

repeat(cronExpr)(operation)
```

The `repeat` API uses `CronSchedule` underneath, but since it does not provide any configuration beyond `CronExpr` there is no need to provide instance of `CronSchedule` directly.

## Operation definition

Similarly to the `repeat` API, the `operation` can be defined:
* directly using a by-name parameter, i.e. `f: => T`
* using a by-name `Either[E, T]`
* or using an arbitrary [error mode](../basics/error-handling.md), accepting the computation in an `F` context: `f: => F[T]`.


## Configuration

The `cron.repeat` requires a `CronExpr`, which defines cron expression on which the schedule will run.

In addition, it is possible to define a custom `shouldContinueOnResult` strategy for deciding if the operation
should continue to be repeated after a successful result returned by the previous operation (defaults to `_: T => true`).

If an operation returns an error, the repeat loop will always be stopped. If an error handling within the operation
is needed, you can use a `retry` inside it (see an example below) or use `scheduled` with `CronSchedule` instead of `cron.repeat`, which allows
full customization.


## Examples

```scala mdoc:compile-only
import ox.UnionMode
import ox.scheduling.cron.{repeat, repeatEither, repeatWithErrorMode}
import scala.concurrent.duration.*
import ox.resilience.{RetryConfig, retry}
import cron4s.*

def directOperation: Int = ???
def eitherOperation: Either[String, Int] = ???
def unionOperation: String | Int = ???

val cronExpr: CronExpr = Cron.unsafeParse("10-35 2,4,6 * ? * *")

// various operation definitions - same syntax
repeat(cronExpr)(directOperation)
repeatEither(cronExpr)(eitherOperation)

// infinite repeats with a custom strategy
def customStopStrategy: Int => Boolean = ???
repeat(cronExpr, shouldContinueOnResult = customStopStrategy)(directOperation)

// custom error mode
repeatWithErrorMode(UnionMode[String])(cronExpr)(unionOperation)

// repeat with retry inside
repeat(cronExpr) {
  retry(RetryConfig.backoff(3, 100.millis))(directOperation)
}
```
