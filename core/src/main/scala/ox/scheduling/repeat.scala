package ox.scheduling

import ox.scheduling.*
import ox.{EitherMode, ErrorMode}

import scala.util.Try

/** Repeats an operation returning a direct result until it succeeds or the config decides to stop.
  *
  * @param config
  *   The repeat config - see [[RepeatConfig]].
  * @param operation
  *   The operation to repeat.
  * @return
  *   The result of the last invocation if the config decides to stop.
  * @throws anything
  *   The exception thrown by the last invocation if the config decides to stop.
  */
def repeat[T](config: RepeatConfig[Throwable, T])(operation: => T): T =
  repeatEither(config)(Try(operation).toEither).fold(throw _, identity)

/** Repeats an operation returning an [[scala.util.Either]] until the config decides to stop. Note that any exceptions thrown by the
  * operation aren't caught and effectively interrupt the repeat loop.
  *
  * @param config
  *   The repeat config - see [[RepeatConfig]].
  * @param operation
  *   The operation to repeat.
  * @return
  *   The result of the last invocation if the config decides to stop.
  */
def repeatEither[E, T](config: RepeatConfig[E, T])(operation: => Either[E, T]): Either[E, T] =
  repeatWithErrorMode(EitherMode[E])(config)(operation)

/** Repeats an operation using the given error mode until the config decides to stop. Note that any exceptions thrown by the operation
  * aren't caught and effectively interrupt the repeat loop.
  *
  * @param em
  *   The error mode to use, which specifies when a result value is considered success, and when a failure.
  * @param config
  *   The repeat config - see [[RepeatConfig]].
  * @param operation
  *   The operation to repeat.
  * @return
  *   The result of the last invocation if the config decides to stop.
  */
def repeatWithErrorMode[E, F[_], T](em: ErrorMode[E, F])(config: RepeatConfig[E, T])(operation: => F[T]): F[T] =
  scheduledWithErrorMode[E, F, T](em)(config.toScheduledConfig)(operation)
