package ox.scheduling

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
  *   The schedule which determines the intervals between invocations and number of attempts to execute the operation.
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
  def schedule(newSchedule: Schedule): RepeatConfig[E, T] = copy(schedule = newSchedule)

  def shouldContinueOnResult(newShouldContinueOnResult: T => Boolean): RepeatConfig[E, T] =
    copy(shouldContinueOnResult = newShouldContinueOnResult)

  def toScheduledConfig: ScheduledConfig[E, T] =
    val afterAttempt: (Int, Either[E, T]) => ScheduleStop = (_, attempt) =>
      attempt match
        case Left(_)      => ScheduleStop.Yes
        case Right(value) => ScheduleStop(!shouldContinueOnResult(value))

    ScheduledConfig(schedule, afterAttempt, SleepMode.StartToStart)
  end toScheduledConfig
end RepeatConfig
