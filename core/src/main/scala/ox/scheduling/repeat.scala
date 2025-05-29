package ox.scheduling

import ox.EitherMode
import ox.ErrorMode
import ox.scheduling.*

import scala.util.Try

/** Repeats an operation returning a direct result until it succeeds or the config decides to stop.
  *
  * [[repeat]] is a special case of [[scheduled]] with a given set of defaults.
  *
  * @param config
  *   The repeat config. Includes a [[Schedule]] which determines the intervals between invocations and number of repetitions of the
  *   operation.
  * @param operation
  *   The operation to repeat.
  * @return
  *   The result of the last invocation if the config decides to stop.
  * @throws anything
  *   The exception thrown by the last invocation if the config decides to stop.
  * @see
  *   [[scheduled]]
  */
def repeat[T](config: RepeatConfig[Throwable, T])(operation: => T): T =
  repeatEither(config)(Try(operation).toEither).fold(throw _, identity)

/** Repeats an operation returning a direct result until it succeeds or the config decides to stop. Uses the default [[RepeatConfig]], with
  * the given [[Schedule]].
  *
  * [[repeat]] is a special case of [[scheduled]] with a given set of defaults.
  *
  * @param schedule
  *   The schedule which determines the intervals between invocations and number of attempts to execute the operation.
  * @param operation
  *   The operation to repeat.
  * @return
  *   The result of the last invocation if the config decides to stop.
  * @throws anything
  *   The exception thrown by the last invocation if the config decides to stop.
  * @see
  *   [[scheduled]]
  */
def repeat[T](schedule: Schedule)(operation: => T): T = repeat(RepeatConfig(schedule))(operation)

/** Repeats an operation returning an [[scala.util.Either]] until the config decides to stop. Note that any exceptions thrown by the
  * operation aren't caught and effectively interrupt the repeat loop.
  *
  * [[repeatEither]] is a special case of [[scheduledEither]] with a given set of defaults.
  *
  * @param config
  *   The repeat config. Includes a [[Schedule]] which determines the intervals between invocations and number of repetitions of the
  *   operation.
  * @param operation
  *   The operation to repeat.
  * @return
  *   The result of the last invocation if the config decides to stop.
  * @see
  *   [[scheduledEither]]
  */
def repeatEither[E, T](config: RepeatConfig[E, T])(operation: => Either[E, T]): Either[E, T] =
  repeatWithErrorMode(EitherMode[E])(config)(operation)

/** Repeats an operation returning an [[scala.util.Either]] until the config decides to stop. Note that any exceptions thrown by the
  * operation aren't caught and effectively interrupt the repeat loop. Uses the default [[RepeatConfig]], with the given [[Schedule]].
  *
  * [[repeatEither]] is a special case of [[scheduledEither]] with a given set of defaults.
  *
  * @param schedule
  *   The schedule which determines the intervals between invocations and number of attempts to execute the operation.
  * @param operation
  *   The operation to repeat.
  * @return
  *   The result of the last invocation if the config decides to stop.
  * @see
  *   [[scheduledEither]]
  */
def repeatEither[E, T](schedule: Schedule)(operation: => Either[E, T]): Either[E, T] = repeatEither(RepeatConfig(schedule))(operation)

/** Repeats an operation using the given error mode until the config decides to stop. Note that any exceptions thrown by the operation
  * aren't caught and effectively interrupt the repeat loop.
  *
  * [[repeatWithErrorMode]] is a special case of [[scheduledWithErrorMode]] with a given set of defaults.
  *
  * @param em
  *   The error mode to use, which specifies when a result value is considered success, and when a failure.
  * @param config
  *   The repeat config. Includes a [[Schedule]] which determines the intervals between invocations and number of repetitions of the
  *   operation.
  * @param operation
  *   The operation to repeat.
  * @return
  *   The result of the last invocation if the config decides to stop.
  * @see
  *   [[scheduledWithErrorMode]]
  */
def repeatWithErrorMode[E, F[_], T](em: ErrorMode[E, F])(config: RepeatConfig[E, T])(operation: => F[T]): F[T] =
  scheduledWithErrorMode[E, F, T](em)(config.toScheduledConfig)(operation)

/** Repeats an operation using the given error mode until the config decides to stop. Note that any exceptions thrown by the operation
  * aren't caught and effectively interrupt the repeat loop. Uses the default [[RepeatConfig]], with the given [[Schedule]].
  *
  * [[repeatWithErrorMode]] is a special case of [[scheduledWithErrorMode]] with a given set of defaults.
  *
  * @param em
  *   The error mode to use, which specifies when a result value is considered success, and when a failure.
  * @param schedule
  *   The schedule which determines the intervals between invocations and number of attempts to execute the operation.
  * @param operation
  *   The operation to repeat.
  * @return
  *   The result of the last invocation if the config decides to stop.
  * @see
  *   [[scheduledWithErrorMode]]
  */
def repeatWithErrorMode[E, F[_], T](em: ErrorMode[E, F])(schedule: Schedule)(operation: => F[T]): F[T] =
  repeatWithErrorMode[E, F, T](em)(RepeatConfig(schedule))(operation)
