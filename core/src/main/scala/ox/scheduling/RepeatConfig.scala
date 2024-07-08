package ox.scheduling

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.duration.DurationInt

/** A config that defines how to repeat an operation.
  *
  * [[Schedule]] provides the interval between subsequent invocations, which guarantees that the next operation will start no sooner than
  * the specified interval after the previous operations has finished. If the previous operations takes longer than the interval, the next
  * operation will start immediately after the previous one has finished.
  *
  * @param schedule
  *   The repeat schedule which determines the maximum number of invocations and the interval between subsequent invocations. See
  *   [[Schedule]] for more details.
  * @param shouldContinueOnError
  *   A function that determines whether to continue the loop after an error. The function receives the error that was emitted by the last
  *   invocation. Defaults to [[_ => false]].
  * @param shouldContinueOnResult
  *   A function that determines whether to continue the loop after a success. The function receives the value that was emitted by the last
  *   invocation. Defaults to [[_ => true]].
  * @tparam E
  *   The error type of the operation. For operations returning a `T` or a `Try[T]`, this is fixed to `Throwable`. For operations returning
  *   an `Either[E, T]`, this can be any `E`.
  * @tparam T
  *   The successful result type for the operation.
  */
case class RepeatConfig[E, T](
    schedule: Schedule,
    shouldContinueOnError: E => Boolean = (_: E) => false,
    shouldContinueOnResult: T => Boolean = (_: T) => true
) {
  def toScheduledConfig: ScheduledConfig[E, T] = ScheduledConfig(
    schedule,
    shouldContinueOnError = shouldContinueOnError,
    shouldContinueOnResult = shouldContinueOnResult,
    delayPolicy = DelayPolicy.SinceTheStartOfTheLastInvocation
  )
}

object RepeatConfig:
  /** Creates a config that repeats up to a given number of times, with no delay between subsequent invocations.
    *
    * This is a shorthand for {{{RepeatConfig(Schedule.Immediate(repeats))}}}
    *
    * @param maxInvocations
    *   The maximum number of invocations.
    */
  def immediate[E, T](maxInvocations: Int): RepeatConfig[E, T] = RepeatConfig(Schedule.Immediate(maxInvocations))

  /** Creates a config that repeats indefinitely, with no delay between subsequent invocations.
    *
    * This is a shorthand for {{{RepeatConfig(Schedule.Immediate.forever)}}}
    */
  def immediateForever[E, T]: RepeatConfig[E, T] = RepeatConfig(Schedule.Immediate.forever)

  /** Creates a config that repeats up to a given number of times, with a fixed interval between subsequent invocations.
    *
    * This is a shorthand for {{{RepeatConfig(Schedule.Delay(maxInvocations, interval))}}}
    *
    * @param maxInvocations
    *   The maximum number of invocations.
    * @param interval
    *   The interval between subsequent attempts.
    */
  def fixedRate[E, T](maxInvocations: Int, interval: FiniteDuration): RepeatConfig[E, T] = RepeatConfig(
    Schedule.Delay(maxInvocations, interval)
  )

  /** Creates a config that repeats indefinitely, with a fixed interval between subsequent invocations.
    *
    * This is a shorthand for {{{RepeatConfig(Schedule.Delay.forever(interval))}}}
    *
    * @param interval
    *   The delay between subsequent invocations.
    */
  def fixedRateForever[E, T](interval: FiniteDuration): RepeatConfig[E, T] = RepeatConfig(Schedule.Delay.forever(interval))

  /** Creates a config that repeats up to a given number of times, with an increasing interval (backoff) between subsequent attempts.
    *
    * The backoff is exponential with base 2 (i.e. the next interval is twice as long as the previous one), starting at the given first
    * interval and capped at the given maximum interval.
    *
    * This is a shorthand for {{{RetryConfig(Schedule.Backoff(maxRetries, initialDelay, maxDelay, jitter))}}}
    *
    * @param maxInvocations
    *   The maximum number of invocations.
    * @param firstInterval
    *   The interval between the first and the second operation.
    * @param maxInterval
    *   The maximum interval between subsequent invocations. Defaults to 1 minute.
    * @param jitter
    *   A random factor used for calculating the interval between subsequent retries. See [[Jitter]] for more details. Defaults to no
    *   jitter, i.e. an exponential backoff with no adjustments.
    */
  def backoff[E, T](
      maxInvocations: Int,
      firstInterval: FiniteDuration,
      maxInterval: FiniteDuration = 1.minute,
      jitter: Jitter = Jitter.None
  ): RepeatConfig[E, T] = RepeatConfig(Schedule.Exponential(maxInvocations, firstInterval, maxInterval, jitter))

  /** Creates a config that repeats indefinitely, with an increasing interval (backoff) between subsequent invocations.
    *
    * The backoff is exponential with base 2 (i.e. the next interval is twice as long as the previous one), starting at the given first
    * interval and capped at the given maximum interval.
    *
    * This is a shorthand for {{{RetryConfig(Schedule.Backoff.forever(initialDelay, maxDelay, jitter))}}}
    *
    * @param firstInterval
    *   The interval between the first and the second operation.
    * @param maxInterval
    *   The maximum interval between subsequent invocations. Defaults to 1 minute.
    * @param jitter
    *   A random factor used for calculating the interval between subsequent retries. See [[Jitter]] for more details. Defaults to no
    *   jitter, i.e. an exponential backoff with no adjustments.
    */
  def backoffForever[E, T](
      firstInterval: FiniteDuration,
      maxInterval: FiniteDuration = 1.minute,
      jitter: Jitter = Jitter.None
  ): RepeatConfig[E, T] = RepeatConfig(Schedule.Exponential.forever(firstInterval, maxInterval, jitter))
