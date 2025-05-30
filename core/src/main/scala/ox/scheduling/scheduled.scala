package ox.scheduling

import ox.EitherMode
import ox.ErrorMode
import ox.sleep

import scala.annotation.tailrec
import scala.concurrent.duration.DurationLong
import scala.concurrent.duration.FiniteDuration
import scala.util.Try

/** The mode that specifies how to interpret the duration provided by the schedule. */
enum SleepMode:
  /** The interval refers to the beginning of each operation. Guarantees that the next operation will start no sooner than the specified
    * duration after the previous operation has started. If the previous operation takes longer than the interval, the next operation will
    * be started immediately after the previous one has finished.
    */
  case StartToStart

  /** The interval refers to the pause between complete operation invocations. Sleeps the duration provided by the schedule before the next
    * operation starts.
    */
  case EndToStart
end SleepMode

/** @see [[ScheduleConfig.afterAttempt]] */
enum ScheduleStop(val stop: Boolean):
  case Yes extends ScheduleStop(true)
  case No extends ScheduleStop(false)

object ScheduleStop:
  def apply(stop: Boolean): ScheduleStop = if stop then Yes else No

/** A config that defines how to schedule an operation.
  *
  * @param schedule
  *   The schedule which determines the maximum number of invocations and the duration between subsequent invocations. See [[Schedule]] for
  *   more details.
  * @param afterAttempt
  *   A callback invoked after every attempt, with the current invocation number (starting from 1) and the result of the operation. Might
  *   decide to short-circuit further attempts, and stop the schedule. Schedule configuration (e.g. max number of attempts) takes
  *   precedence.
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
    afterAttempt: (Int, Either[E, T]) => ScheduleStop = (_, _: Either[E, T]) => ScheduleStop.No,
    sleepMode: SleepMode = SleepMode.StartToStart
):
  def schedule(newSchedule: Schedule): ScheduledConfig[E, T] = copy(schedule = newSchedule)

  def afterAttempt(newAfterAttempt: (Int, Either[E, T]) => ScheduleStop): ScheduledConfig[E, T] =
    copy(afterAttempt = newAfterAttempt)

  def sleepMode(newSleepMode: SleepMode): ScheduledConfig[E, T] = copy(sleepMode = newSleepMode)
end ScheduledConfig

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
  def loop(invocation: Int, intervals: LazyList[FiniteDuration], lastDuration: Option[FiniteDuration]): F[T] =
    def sleepIfNeeded(startTimestamp: Long, nextDelay: FiniteDuration) =
      val delay = config.sleepMode match
        case SleepMode.StartToStart =>
          val elapsed = System.nanoTime() - startTimestamp
          val remaining = nextDelay.toNanos - elapsed
          remaining.nanos
        case SleepMode.EndToStart => nextDelay
      if delay.toMillis > 0 then sleep(delay)
      delay
    end sleepIfNeeded

    val startTimestamp = System.nanoTime()
    val nextDelay = intervals.headOption
    operation match
      case v if em.isError(v) =>
        val error = em.getError(v)
        val shouldStop = config.afterAttempt(invocation, Left(error))

        nextDelay match
          case Some(nd) if !shouldStop.stop =>
            val delay = sleepIfNeeded(startTimestamp, nd)
            loop(invocation + 1, intervals.tail, Some(delay))
          case _ => v
      case v =>
        val result = em.getT(v)
        val shouldStop = config.afterAttempt(invocation, Right(result))

        nextDelay match
          case Some(nd) if !shouldStop.stop =>
            val delay = sleepIfNeeded(startTimestamp, nd)
            loop(invocation + 1, intervals.tail, Some(delay))
          case _ => v
    end match
  end loop

  config.schedule.initialDelay.foreach(sleep)

  loop(1, config.schedule.intervals(), None)
end scheduledWithErrorMode
