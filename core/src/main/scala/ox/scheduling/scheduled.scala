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
end SleepMode

enum ScheduleContinue(val continue: Boolean):
  case Yes extends ScheduleContinue(true)
  case No extends ScheduleContinue(false)

end ScheduleContinue

object ScheduleContinue:
  def fromBool(predicate: Boolean): ScheduleContinue =
    if predicate then Yes
    else No
end ScheduleContinue

/** A config that defines how to schedule an operation.
  *
  * @param schedule
  *   The schedule which determines the maximum number of invocations and the duration between subsequent invocations. See [[Schedule]] for
  *   more details.
  * @param afterAttempt
  *   A function that determines if schedule should continue. It is invoked after every attempt with current invocations number (starting
  *   from 1) and the result of an operation. It can contain side effects.
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
    afterAttempt: (Int, Either[E, T]) => ScheduleContinue = (_, attempt: Either[E, T]) =>
      attempt.map(_ => ScheduleContinue.Yes).getOrElse(ScheduleContinue.No),
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
    end sleepIfNeeded

    val startTimestamp = System.nanoTime()
    operation match
      case v if em.isError(v) =>
        val error = em.getError(v)
        val shouldContinue = config.afterAttempt(invocation, Left(error))

        if remainingInvocations.forall(_ > 0) && shouldContinue.continue then
          val delay = sleepIfNeeded(startTimestamp)
          loop(invocation + 1, remainingInvocations.map(_ - 1), Some(delay))
        else v
      case v =>
        val result = em.getT(v)
        val shouldContinue = config.afterAttempt(invocation, Right(result))

        if remainingInvocations.forall(_ > 0) && shouldContinue.continue then
          val delay = sleepIfNeeded(startTimestamp)
          loop(invocation + 1, remainingInvocations.map(_ - 1), Some(delay))
        else v
    end match
  end loop

  val remainingInvocations = config.schedule match
    case finiteSchedule: Schedule.Finite => Some(finiteSchedule.maxRepeats)
    case _                               => None

  val initialDelay = config.schedule.initialDelay
  if initialDelay.toMillis > 0 then sleep(initialDelay)

  loop(1, remainingInvocations, None)
end scheduledWithErrorMode
