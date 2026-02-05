# Cron scheduler

Dependency:

```scala
"com.softwaremill.ox" %% "cron" % "1.0.2"
```

This module allows to run schedules based on cron expressions from [cron4s](https://github.com/alonsodomin/cron4s).

`CronSchedule` can be used in all places that requires `Schedule` especially in [repeat](../scheduling/repeat.md) scenarios.

For defining `CronExpr` see [cron4s documentation](https://www.alonsodomin.me/cron4s/userguide/index.html).

## Api

The cron module exposes methods for creating `Schedule` based on `CronExpr`.

```scala
import ox.scheduling.cron.*
import cron4s.*

repeat(CronSchedule.unsafeFromString("10-35 2,4,6 * ? * *"))(operation)
```

## Operation definition

Methods from `ox.scheduling.cron.CronSchedule` define `Schedule`, so they can be plugged into `RepeatConfig` and used with `repeat` API.

## Configuration

All configuration beyond `CronExpr` is provided by the `repeat` API. If an error handling within the operation
is needed, you can use a `retry` inside it (see an example below) or use `scheduled` with `CronSchedule` instead of `repeat`, which allows
full customization.

## Examples

```scala
import ox.UnionMode
import ox.scheduling.cron.CronSchedule
import scala.concurrent.duration.*
import ox.resilience.{RetryConfig, retry}
import ox.scheduling.*
import cron4s.*

def directOperation: Int = ???
def eitherOperation: Either[String, Int] = ???
def unionOperation: String | Int = ???

val cronExpr: CronExpr = Cron.unsafeParse("10-35 2,4,6 * ? * *")

// various operation definitions - same syntax
repeat(CronSchedule.fromCronExpr(cronExpr))(directOperation)
repeatEither(CronSchedule.fromCronExpr(cronExpr))(eitherOperation)

// infinite repeats with a custom strategy
def customStopStrategy: Int => Boolean = ???
repeat(RepeatConfig(CronSchedule.fromCronExpr(cronExpr), customStopStrategy))(directOperation)

// custom error mode
repeatWithErrorMode(UnionMode[String])(RepeatConfig(CronSchedule.fromCronExpr(cronExpr)))(unionOperation)

// repeat with retry inside
repeat(RepeatConfig(CronSchedule.fromCronExpr(cronExpr))) {
  retry(Schedule.exponentialBackoff(100.millis).maxRetries(3))(directOperation)
}
```
