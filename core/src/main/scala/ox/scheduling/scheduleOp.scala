package ox.scheduling

import ox.{EitherMode, ErrorMode, sleep}

import scala.annotation.tailrec
import scala.concurrent.duration.FiniteDuration
import scala.util.Try

def schedule[T](
    schedule: Schedule,
    onTick: (Int, Either[Throwable, T]) => Unit = (_: Int, _: Either[Throwable, T]) => (),
    shouldRetryOnError: Throwable => Boolean = (_: Throwable) => false,
    shouldContinue: T => Boolean = (_: T) => true
)(operation: => T): T =
  scheduleEither(schedule, onTick, shouldRetryOnError, shouldContinue)(Try(operation).toEither).fold(throw _, identity)

def scheduleEither[E, T](
    schedule: Schedule,
    onTick: (Int, Either[E, T]) => Unit = (_: Int, _: Either[E, T]) => (),
    shouldRetryOnError: E => Boolean = (_: E) => false,
    shouldContinue: T => Boolean = (_: T) => true
)(operation: => Either[E, T]): Either[E, T] =
  scheduleWithErrorMode(EitherMode[E])(schedule, onTick, shouldRetryOnError, shouldContinue)(operation)

def scheduleWithErrorMode[E, F[_], T](em: ErrorMode[E, F])(
    schedule: Schedule,
    onTick: (Int, Either[E, T]) => Unit = (_: Int, _: Either[E, T]) => (),
    shouldRetryOnError: E => Boolean = (_: E) => false,
    shouldContinue: T => Boolean = (_: T) => true
)(operation: => F[T]): F[T] =
  @tailrec
  def loop(attempt: Int, remainingAttempts: Option[Int], lastDelay: Option[FiniteDuration]): F[T] =
    def sleepIfNeeded(startTimestamp: Long) =
      val delay = schedule.nextDelay(attempt, lastDelay, Some(startTimestamp))
      if delay.toMillis > 0 then sleep(delay)
      delay

    val startTimestamp = System.nanoTime()
    operation match
      case v if em.isError(v) =>
        val error = em.getError(v)
        onTick(attempt, Left(error))

        if shouldRetryOnError(error) && remainingAttempts.forall(_ > 0) then
          val delay = sleepIfNeeded(startTimestamp)
          loop(attempt + 1, remainingAttempts.map(_ - 1), Some(delay))
        else v
      case v =>
        val result = em.getT(v)
        onTick(attempt, Right(result))

        if shouldContinue(result) && remainingAttempts.forall(_ > 0) then
          val delay = sleepIfNeeded(startTimestamp)
          loop(attempt + 1, remainingAttempts.map(_ - 1), Some(delay))
        else v

  val remainingAttempts = schedule match
    case finiteSchedule: Schedule.Finite => Some(finiteSchedule.maxRetries)
    case _                               => None

  loop(1, remainingAttempts, None)
