package ox.scheduling

import ox.{EitherMode, ErrorMode, sleep}

import scala.annotation.tailrec
import scala.concurrent.duration.{FiniteDuration, DurationLong}
import scala.util.Try

/** The mode that specifies how to interpret the duration provided by the schedule. */
enum SleepMode:
  /** Interval (since the start of the last operation), i.e. guarantees that the next operation will start no sooner than the specified
    * duration after the previous operation has started. If the previous operation takes longer than the interval, the next operation will
    * be started immediately after the previous one has finished.
    */
  case Interval

  /** Delay (since the end of the last operation), i.e. sleeps the duration provided by the schedule before the next operation starts.
    */
  case Delay

/** A config that defines how to schedule an operation.
  *
  * @param schedule
  *   The schedule which determines the maximum number of invocations and the duration between subsequent invocations. See [[Schedule]] for
  *   more details.
  * @param onOperationResult
  *   A function that is invoked after each invocation. The callback receives the number of the current invocations number (starting from 1)
  *   and the result of the operation. The result is either a successful value or an error.
  * @param shouldContinueOnError
  *   A function that determines whether to continue the loop after an error. The function receives the error that was emitted by the last
  *   invocation. Defaults to [[_ => false]].
  * @param shouldContinueOnResult
  *   A function that determines whether to continue the loop after a success. The function receives the value that was emitted by the last
  *   invocation. Defaults to [[_ => true]].
  * @param sleepMode
  *   The mode that specifies how to interpret the duration provided by the schedule. See [[SleepMode]] for more details.
  * @tparam E
  *   The error type of the operation. For operations returning a `T` or a `Try[T]`, this is fixed to `Throwable`. For operations returning
  *   an `Either[E, T]`, this can be any `E`.
  * @tparam T
  *   The successful result type for the operation.
  */
case class ScheduledConfig[E, T](
    schedule: Schedule,
    onOperationResult: (Int, Either[E, T]) => Unit = (_: Int, _: Either[E, T]) => (),
    shouldContinueOnError: E => Boolean = (_: E) => false,
    shouldContinueOnResult: T => Boolean = (_: T) => true,
    sleepMode: SleepMode = SleepMode.Interval
)

/** Schedules an operation returning a direct result until it succeeds or the config decides to stop.
  *
  * @param config
  *   The repeat config - see [[ScheduledConfig]].
  * @param operation
  *   The operation to schedule.
  * @return
  *   The result of the last invocation if the config decides to stop.
  * @throws anything
  *   The exception thrown by the last invocation if the config decides to stop.
  */
def scheduled[T](config: ScheduledConfig[Throwable, T])(operation: => T): T =
  scheduledEither(config)(Try(operation).toEither).fold(throw _, identity)

/** Schedules an operation returning an [[scala.util.Either]] until the config decides to stop. Note that any exceptions thrown by the
  * operation aren't caught and effectively interrupt the schedule loop.
  *
  * @param config
  *   The schedule config - see [[ScheduledConfig]].
  * @param operation
  *   The operation to schedule.
  * @return
  *   The result of the last invocation if the config decides to stop.
  */
def scheduledEither[E, T](config: ScheduledConfig[E, T])(operation: => Either[E, T]): Either[E, T] =
  scheduledWithErrorMode(EitherMode[E])(config)(operation)

/** Schedules an operation using the given error mode until the config decides to stop. Note that any exceptions thrown by the operation
  * aren't caught and effectively interrupt the schedule loop.
  *
  * @param em
  *   The error mode to use, which specifies when a result value is considered success, and when a failure.
  * @param config
  *   The schedule config - see [[ScheduledConfig]].
  * @param operation
  *   The operation to schedule.
  * @return
  *   The result of the last invocation if the config decides to stop.
  */
def scheduledWithErrorMode[E, F[_], T](em: ErrorMode[E, F])(config: ScheduledConfig[E, T])(operation: => F[T]): F[T] =
  @tailrec
  def loop(invocation: Int, remainingInvocations: Option[Int], lastDuration: Option[FiniteDuration]): F[T] =
    def sleepIfNeeded(startTimestamp: Long) =
      val nextDuration = config.schedule.nextDuration(invocation, lastDuration)
      val delay = config.sleepMode match
        case SleepMode.Interval =>
          val elapsed = System.nanoTime() - startTimestamp
          val remaining = nextDuration.toNanos - elapsed
          remaining.nanos
        case SleepMode.Delay => nextDuration
      if delay.toMillis > 0 then sleep(delay)
      delay

    val startTimestamp = System.nanoTime()
    operation match
      case v if em.isError(v) =>
        val error = em.getError(v)
        config.onOperationResult(invocation, Left(error))

        if config.shouldContinueOnError(error) && remainingInvocations.forall(_ > 0) then
          val delay = sleepIfNeeded(startTimestamp)
          loop(invocation + 1, remainingInvocations.map(_ - 1), Some(delay))
        else v
      case v =>
        val result = em.getT(v)
        config.onOperationResult(invocation, Right(result))

        if config.shouldContinueOnResult(result) && remainingInvocations.forall(_ > 0) then
          val delay = sleepIfNeeded(startTimestamp)
          loop(invocation + 1, remainingInvocations.map(_ - 1), Some(delay))
        else v

  val remainingInvocations = config.schedule match
    case finiteSchedule: Schedule.Finite => Some(finiteSchedule.maxRepeats)
    case _                               => None

  val initialDelay = config.schedule.initialDelay
  if initialDelay.toMillis > 0 then sleep(initialDelay)

  loop(1, remainingInvocations, None)
