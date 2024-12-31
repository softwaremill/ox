package ox.scheduling.cron

import cron4s.CronExpr
import ox.scheduling.*
import ox.{EitherMode, ErrorMode}

import scala.util.Try

/** Repeats an operation returning a direct result based on cron expression until it succeeds we decide to stop.
  *
  * [[repeat]] is a special case of [[scheduled]] with a given set of defaults.
  *
  * @param cron
  *   [[CronExpr]] to create schedule from
  * @param shouldContinueOnResult
  *   A function that determines whether to continue the loop after a success. The function receives the value that was emitted by the last
  *   invocation. Defaults to [[_ => true]].
  * @param operation
  *   The operation to repeat.
  * @return
  *   The result of the last invocation if the config decides to stop.
  * @throws anything
  *   The exception thrown by the last invocation if the config decides to stop.
  * @see
  *   [[scheduled]]
  */
def repeat[T](cron: CronExpr, shouldContinueOnResult: T => Boolean = (_: T) => true)(operation: => T): T =
  repeatEither[Throwable, T](cron, shouldContinueOnResult)(Try(operation).toEither).fold(throw _, identity)

/** Repeats an operation based on cron expression returning an [[scala.util.Either]] until we decide to stop. Note that any exceptions
  * thrown by the operation aren't caught and effectively interrupt the repeat loop.
  *
  * [[repeatEither]] is a special case of [[scheduledEither]] with a given set of defaults.
  *
  * @param cron
  *   [[CronExpr]] to create schedule from
  * @param shouldContinueOnResult
  *   A function that determines whether to continue the loop after a success. The function receives the value that was emitted by the last
  *   invocation. Defaults to [[_ => true]].
  * @param operation
  *   The operation to repeat.
  * @return
  *   The result of the last invocation if the config decides to stop.
  * @see
  *   [[scheduledEither]]
  */
def repeatEither[E, T](cron: CronExpr, shouldContinueOnResult: T => Boolean = (_: T) => true)(operation: => Either[E, T]): Either[E, T] =
  repeatWithErrorMode(EitherMode[E])(cron, shouldContinueOnResult)(operation)

/** Repeats an operation based on cron expression using the given error mode until we decide to stop. Note that any exceptions thrown by the
  * operation aren't caught and effectively interrupt the repeat loop.
  *
  * [[repeatWithErrorMode]] is a special case of [[scheduledWithErrorMode]] with a given set of defaults.
  *
  * @param em
  *   The error mode to use, which specifies when a result value is considered success, and when a failure.
  * @param cron
  *   [[CronExpr]] to create schedule from
  * @param shouldContinueOnResult
  *   A function that determines whether to continue the loop after a success. The function receives the value that was emitted by the last
  *   invocation. Defaults to [[_ => true]].
  * @param operation
  *   The operation to repeat.
  * @return
  *   The result of the last invocation if the config decides to stop.
  * @see
  *   [[scheduledWithErrorMode]]
  */
def repeatWithErrorMode[E, F[_], T](em: ErrorMode[E, F])(cron: CronExpr, shouldContinueOnResult: T => Boolean = (_: T) => true)(
    operation: => F[T]
): F[T] =
  scheduledWithErrorMode[E, F, T](em)(RepeatConfig(CronSchedule(cron), shouldContinueOnResult).toScheduledConfig)(operation)
