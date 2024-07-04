package ox.scheduling

import ox.scheduling.*
import ox.{EitherMode, ErrorMode, sleep}

import scala.annotation.tailrec
import scala.concurrent.duration.*
import scala.util.Try

def repeat[T](config: RepeatConfig[Throwable, T])(operation: => T): T =
  repeatEither(config)(Try(operation).toEither).fold(throw _, identity)

def repeatEither[E, T](config: RepeatConfig[E, T])(operation: => Either[E, T]): Either[E, T] =
  repeatWithErrorMode(EitherMode[E])(config)(operation)

def repeatWithErrorMode[E, F[_], T](em: ErrorMode[E, F])(config: RepeatConfig[E, T])(operation: => F[T]): F[T] =
  runScheduledWithErrorMode(em)(
    config.schedule,
    shouldContinueOnError = config.shouldContinueOnError,
    shouldContinue = config.shouldContinue
  )(operation)
