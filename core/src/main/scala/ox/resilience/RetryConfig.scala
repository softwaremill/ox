package ox.resilience

import ox.scheduling.Schedule
import ox.scheduling.ScheduleStop
import ox.scheduling.ScheduledConfig
import ox.scheduling.SleepMode

/** A config that defines how to retry failing operations.
  *
  * It is a special case of [[ScheduledConfig]] with [[ScheduledConfig.sleepMode]] always set to [[SleepMode.Delay]]
  *
  * @param schedule
  *   The schedule which determines the intervals between invocations and number of attempts to execute the operation.
  * @param resultPolicy
  *   A policy that allows to customize when a non-erroneous result is considered successful and when an error is worth retrying (which
  *   allows for failing fast on certain errors). See [[ResultPolicy]] for more details.
  * @param onRetry
  *   A function that is invoked after each retry attempt The callback receives the number of the current retry attempt (starting from 1)
  *   and the result of the operation that was attempted. The result is either a successful value or an error. The callback can be used to
  *   log information about the retry attempts, or to perform other side effects. It will always be invoked at least once (for a successful
  *   operation run). By default, the callback does nothing.
  * @tparam E
  *   The error type of the operation. For operations returning a `T` or a `Try[T]`, this is fixed to `Throwable`. For operations returning
  *   an `Either[E, T]`, this can be any `E`.
  * @tparam T
  *   The successful result type for the operation.
  */
case class RetryConfig[E, T](
    schedule: Schedule,
    resultPolicy: ResultPolicy[E, T] = ResultPolicy.default[E, T],
    onRetry: (Int, Either[E, T]) => Unit = (_: Int, _: Either[E, T]) => ()
):
  def toScheduledConfig: ScheduledConfig[E, T] =
    val afterAttempt: (Int, Either[E, T]) => ScheduleStop = (attemptNum, attempt) =>
      onRetry(attemptNum, attempt)
      attempt match
        case Left(value)  => ScheduleStop(!resultPolicy.isWorthRetrying(value))
        case Right(value) => ScheduleStop(resultPolicy.isSuccess(value))

    ScheduledConfig(schedule, afterAttempt, SleepMode.EndToStart)
  end toScheduledConfig
end RetryConfig
