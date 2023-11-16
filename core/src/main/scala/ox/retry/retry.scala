package ox.retry

import scala.concurrent.duration.*
import scala.util.Try

sealed trait Jitter

object Jitter:
  case object None extends Jitter
  case object Full extends Jitter
  case object Equal extends Jitter
  case object Decorrelated extends Jitter

trait RetryPolicy:
  def maxRetries: Int
  def nextDelay(attempt: Int): FiniteDuration

object RetryPolicy:
  case class Direct(maxRetries: Int) extends RetryPolicy:
    def nextDelay(attempt: Int): FiniteDuration = Duration.Zero

  case class Delay(maxRetries: Int, delay: FiniteDuration) extends RetryPolicy:
    def nextDelay(attempt: Int): FiniteDuration = delay

  case class Backoff(maxRetries: Int, initialDelay: FiniteDuration, jitter: Jitter = Jitter.None) extends RetryPolicy:
    def nextDelay(attempt: Int): FiniteDuration = initialDelay * Math.pow(2, attempt).toLong // TODO jitter

def retry[T](f: => T)(policy: RetryPolicy): T =
  retry(f, _ => true)(policy)

def retry[T](f: => T, isSuccess: T => Boolean)(policy: RetryPolicy): T =
  retry(Try(f), isSuccess)(policy).get

def retry[E, T](f: => Either[E, T])(policy: RetryPolicy)(using dummy: DummyImplicit): Either[E, T] =
  retry(f, _ => true)(policy)(using dummy)

def retry[E, T](f: => Either[E, T], isSuccess: T => Boolean, isWorthRetrying: E => Boolean = (_: E) => true)(policy: RetryPolicy)(using
    dummy: DummyImplicit
): Either[E, T] =
  def loop(remainingAttempts: Int): Either[E, T] =
    def nextAttemptOr(e: => Either[E, T]) =
      if remainingAttempts > 0 then
        val delay = policy.nextDelay(policy.maxRetries - remainingAttempts).toMillis
        if delay > 0 then Thread.sleep(delay)
        loop(remainingAttempts - 1)
      else e

    f match
      case left @ Left(error) =>
        if isWorthRetrying(error) then nextAttemptOr(left)
        else left
      case right @ Right(result) if !isSuccess(result) => nextAttemptOr(right)
      case right                                       => right

  loop(policy.maxRetries)

def retry[T](f: => Try[T])(policy: RetryPolicy)(using dummy1: DummyImplicit, dummy2: DummyImplicit): Try[T] =
  retry(f, _ => true)(policy)(using dummy1, dummy2)

def retry[T](f: => Try[T], isSuccess: T => Boolean)(policy: RetryPolicy)(using dummy1: DummyImplicit, dummy2: DummyImplicit): Try[T] =
  retry(f.toEither, isSuccess)(policy)(using dummy1).toTry
