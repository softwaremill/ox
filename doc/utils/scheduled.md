# Scheduled

The `scheduled` functions allow to run an operation according to a given schedule. It is preferred to use `repeat`, 
`retry`, or combination of both functions for most use cases, as they provide a more convenient DSL. In fact `retry`
and `repeat` use `scheduled` internally.

## Operation definition

Similarly to the `retry` and `repeat` APIs, the `operation` can be provided:

* directly using a by-name parameter, i.e. `f: => T`
* using a by-name `Either[E, T]`
* or using an arbitrary [error mode](../basics/error-handling.md), accepting the computation in an `F` 
  context: `f: => F[T]`.

## Configuration

The `scheduled` config consists of:

- a `Schedule`, which indicates how many times the `operation` should be run, provides a duration based on which
  a sleep is calculated and provides an initial delay if configured.
- a `SleepMode`, which determines how the sleep between subsequent operations should be calculated:
  - `Interval` - default for `repeat` operations, where the sleep is calculated as the duration provided by schedule 
    minus the duration of the last operation (can be negative, in which case the next operation occurs immediately).
  - `Delay` - default for `retry` operations, where the sleep is just the duration provided by schedule.
- `afterAttempt` - a callback function that is invoked after each operation and determines if the scheduler loop 
  should continue. Used for `afterAttempt`, `shouldContinueOnError`, `shouldContinueOnResult` and adaptive retries in 
  `retry` API. Defaults to always continuing.

## Schedule

See the [retry](retries.md) documentation for an overview of the available ways to create and modify a `Schedule`.
