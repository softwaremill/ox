package ox.scheduling

import ox.scheduling.*
import ox.{EitherMode, ErrorMode}

import scala.util.Try

def repeat[T](config: RepeatConfig[Throwable, T])(operation: => T): T =
  repeatEither(config)(Try(operation).toEither).fold(throw _, identity)

def repeatEither[E, T](config: RepeatConfig[E, T])(operation: => Either[E, T]): Either[E, T] =
  repeatWithErrorMode(EitherMode[E])(config)(operation)

def repeatWithErrorMode[E, F[_], T](em: ErrorMode[E, F])(config: RepeatConfig[E, T])(operation: => F[T]): F[T] =
  scheduledWithErrorMode[E, F, T](em)(
    ScheduledConfig[E, T](
      config.schedule,
      shouldContinueOnError = config.shouldContinueOnError,
      shouldContinueOnResult = config.shouldContinueOnResult,
      delayPolicy = DelayPolicy.SinceTheStartOfTheLastInvocation
    )
  )(operation)
