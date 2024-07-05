package ox.scheduling

import ox.{EitherMode, ErrorMode, sleep}

import scala.annotation.tailrec
import scala.concurrent.duration.{Duration, FiniteDuration, DurationLong}
import scala.util.Try

enum DelayPolicy:
  case SinceTheStartOfTheLastInvocation, SinceTheEndOfTheLastInvocation

case class RunScheduledConfig[E, T](
    schedule: Schedule,
    onRepeat: (Int, Either[E, T]) => Unit = (_: Int, _: Either[E, T]) => (),
    shouldContinueOnError: E => Boolean = (_: E) => false,
    shouldContinueOnResult: T => Boolean = (_: T) => true,
    delayPolicy: DelayPolicy = DelayPolicy.SinceTheStartOfTheLastInvocation
)

def runScheduled[T](config: RunScheduledConfig[Throwable, T])(operation: => T): T =
  runScheduledEither(config)(Try(operation).toEither).fold(throw _, identity)

def runScheduledEither[E, T](config: RunScheduledConfig[E, T])(operation: => Either[E, T]): Either[E, T] =
  runScheduledWithErrorMode(EitherMode[E])(config)(operation)

def runScheduledWithErrorMode[E, F[_], T](em: ErrorMode[E, F])(config: RunScheduledConfig[E, T])(operation: => F[T]): F[T] =
  @tailrec
  def loop(attempt: Int, remainingAttempts: Option[Int], lastDelay: Option[FiniteDuration]): F[T] =
    def sleepIfNeeded(startTimestamp: Long) =
      val nextDelay = config.schedule.nextDelay(attempt, lastDelay)
      val delay = config.delayPolicy match
        case DelayPolicy.SinceTheStartOfTheLastInvocation =>
          val elapsed = System.nanoTime() - startTimestamp
          val remaining = nextDelay.toNanos - elapsed
          remaining.nanos
        case DelayPolicy.SinceTheEndOfTheLastInvocation => nextDelay
      if delay.toMillis > 0 then sleep(delay)
      delay

    val startTimestamp = System.nanoTime()
    operation match
      case v if em.isError(v) =>
        val error = em.getError(v)
        config.onRepeat(attempt, Left(error))

        if config.shouldContinueOnError(error) && remainingAttempts.forall(_ > 0) then
          val delay = sleepIfNeeded(startTimestamp)
          loop(attempt + 1, remainingAttempts.map(_ - 1), Some(delay))
        else v
      case v =>
        val result = em.getT(v)
        config.onRepeat(attempt, Right(result))

        if config.shouldContinueOnResult(result) && remainingAttempts.forall(_ > 0) then
          val delay = sleepIfNeeded(startTimestamp)
          loop(attempt + 1, remainingAttempts.map(_ - 1), Some(delay))
        else v

  val (initialDelay, remainingAttempts) = config.schedule match
    case finiteSchedule: Schedule.Finite =>
      (finiteSchedule.initialDelay, Some(finiteSchedule.maxRepeats))
    case _ =>
      (Duration.Zero, None)

  if initialDelay.toMillis > 0 then sleep(initialDelay)

  loop(1, remainingAttempts, None)
