package ox.scheduling

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.duration.DurationInt

/** A config that defines how to repeat an operation.
  *
  * [[Schedule]] provides the interval between subsequent invocations, which guarantees that the next operation will start no sooner than
  * the specified duration after the previous operations has finished. If the previous operation takes longer than the interval, the next
  * operation will start immediately after the previous one has finished.
  *
  * It is a special case of [[ScheduledConfig]] with [[ScheduledConfig.sleepMode]] always set to [[SleepMode.Interval]] and a
  * [[ScheduledConfig.afterAttempt]] callback which checks if the result was successful.
  *
  * @param schedule
  *   The repeat schedule which determines the maximum number of invocations and the interval between subsequent invocations. See
  *   [[Schedule]] for more details.
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
    shouldContinueOnResult: T => Boolean = (_: T) => true
):
  def toScheduledConfig: ScheduledConfig[E, T] =
    val afterAttempt: (Int, Either[E, T]) => ScheduleStop = (_, attempt) =>
      attempt match
        case Left(_)      => ScheduleStop.Yes
        case Right(value) => ScheduleStop(!shouldContinueOnResult(value))

    ScheduledConfig(schedule, afterAttempt, SleepMode.Interval)
  end toScheduledConfig
end RepeatConfig

object RepeatConfig:
  /** Creates a config that repeats up to a given number of times, with no interval between subsequent invocations and optional initial
    * delay.
    *
    * @param maxInvocations
    *   The maximum number of invocations.
    * @param initialDelay
    *   The initial delay before the first invocation.
    */
  def immediate[E, T](maxInvocations: Int, initialDelay: Option[FiniteDuration] = None): RepeatConfig[E, T] =
    initialDelay match
      case Some(delay) => RepeatConfig(Schedule.InitialDelay(delay).andThen(Schedule.Immediate(maxInvocations)))
      case None        => RepeatConfig(Schedule.Immediate(maxInvocations))

  /** Creates a config that repeats indefinitely, with no interval between subsequent invocations and optional initial delay.
    *
    * @param initialDelay
    *   The initial delay before the first invocation.
    */
  def immediateForever[E, T](initialDelay: Option[FiniteDuration] = None): RepeatConfig[E, T] =
    initialDelay match
      case Some(delay) => RepeatConfig(Schedule.InitialDelay(delay).andThen(Schedule.Immediate.forever))
      case None        => RepeatConfig(Schedule.Immediate.forever)

  /** Creates a config that repeats up to a given number of times, with a fixed interval between subsequent invocations and optional initial
    * delay.
    *
    * @param maxInvocations
    *   The maximum number of invocations.
    * @param interval
    *   The interval between subsequent attempts.
    * @param initialDelay
    *   The initial delay before the first invocation.
    */
  def fixedRate[E, T](maxInvocations: Int, interval: FiniteDuration, initialDelay: Option[FiniteDuration] = None): RepeatConfig[E, T] =
    initialDelay match
      case Some(delay) => RepeatConfig(Schedule.InitialDelay(delay).andThen(Schedule.Fixed(maxInvocations, interval)))
      case None        => RepeatConfig(Schedule.Fixed(maxInvocations, interval))

  /** Creates a config that repeats indefinitely, with a fixed interval between subsequent invocations and optional initial delay.
    *
    * @param interval
    *   The interval between subsequent invocations.
    */
  def fixedRateForever[E, T](interval: FiniteDuration, initialDelay: Option[FiniteDuration] = None): RepeatConfig[E, T] =
    initialDelay match
      case Some(delay) => RepeatConfig(Schedule.InitialDelay(delay).andThen(Schedule.Fixed.forever(interval)))
      case None        => RepeatConfig(Schedule.Fixed.forever(interval))

  /** Creates a config that repeats up to a given number of times, with an increasing interval (backoff) between subsequent attempts and
    * optional initial delay.
    *
    * The backoff is exponential with base 2 (i.e. the next interval is twice as long as the previous one), starting at the given first
    * interval and capped at the given maximum interval.
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
      jitter: Jitter = Jitter.None,
      initialDelay: Option[FiniteDuration] = None
  ): RepeatConfig[E, T] =
    initialDelay match
      case Some(delay) =>
        RepeatConfig(Schedule.InitialDelay(delay).andThen(Schedule.Backoff(maxInvocations, firstInterval, maxInterval, jitter)))
      case None => RepeatConfig(Schedule.Backoff(maxInvocations, firstInterval, maxInterval, jitter))

  /** Creates a config that repeats indefinitely, with an increasing interval (backoff) between subsequent invocations and optional initial
    * delay.
    *
    * The backoff is exponential with base 2 (i.e. the next interval is twice as long as the previous one), starting at the given first
    * interval and capped at the given maximum interval.
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
      jitter: Jitter = Jitter.None,
      initialDelay: Option[FiniteDuration] = None
  ): RepeatConfig[E, T] =
    initialDelay match
      case Some(delay) =>
        RepeatConfig(Schedule.InitialDelay(delay).andThen(Schedule.Backoff.forever(firstInterval, maxInterval, jitter)))
      case None => RepeatConfig(Schedule.Backoff.forever(firstInterval, maxInterval, jitter))
end RepeatConfig
