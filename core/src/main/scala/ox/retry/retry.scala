package ox.retry

import scala.concurrent.duration.*
import scala.util.{Random, Try}

sealed trait Jitter

object Jitter:
  case object None extends Jitter
  case object Full extends Jitter
  case object Equal extends Jitter
  case object Decorrelated extends Jitter

trait RetryPolicy:
  def maxRetries: Int
  def nextDelay(attempt: Int, lastDelay: Option[FiniteDuration]): FiniteDuration

object RetryPolicy:
  case class Direct(maxRetries: Int) extends RetryPolicy:
    def nextDelay(attempt: Int, lastDelay: Option[FiniteDuration]): FiniteDuration = Duration.Zero

  case class Delay(maxRetries: Int, delay: FiniteDuration) extends RetryPolicy:
    def nextDelay(attempt: Int, lastDelay: Option[FiniteDuration]): FiniteDuration = delay

  case class Backoff(
      maxRetries: Int,
      initialDelay: FiniteDuration,
      maxDelay: FiniteDuration = 1.day,
      jitter: Jitter = Jitter.None
  ) extends RetryPolicy:
    def nextDelay(attempt: Int, lastDelay: Option[FiniteDuration]): FiniteDuration =
      def backoffDelay = Backoff.delay(attempt, initialDelay, maxDelay)

      jitter match
        case Jitter.None => backoffDelay
        case Jitter.Full => Random.between(0, backoffDelay.toMillis).millis
        case Jitter.Equal =>
          val backoff = backoffDelay.toMillis
          (backoff / 2 + Random.between(0, backoff / 2)).millis
        case Jitter.Decorrelated =>
          val last = lastDelay.getOrElse(initialDelay).toMillis
          Random.between(initialDelay.toMillis, last * 3).millis

  private[retry] object Backoff:
    def delay(attempt: Int, initialDelay: FiniteDuration, maxDelay: FiniteDuration): FiniteDuration =
      (initialDelay * Math.pow(2, attempt).toLong).min(maxDelay)

def retry[T](f: => T)(policy: RetryPolicy): T =
  retry(f, _ => true)(policy)

def retry[T](f: => T, isSuccess: T => Boolean)(policy: RetryPolicy): T =
  retry(Try(f), isSuccess)(policy).get

def retry[E, T](f: => Either[E, T])(policy: RetryPolicy)(using dummy: DummyImplicit): Either[E, T] =
  retry(f, _ => true)(policy)(using dummy)

def retry[E, T](f: => Either[E, T], isSuccess: T => Boolean, isWorthRetrying: E => Boolean = (_: E) => true)(policy: RetryPolicy)(using
    dummy: DummyImplicit
): Either[E, T] =
  def loop(remainingAttempts: Int, lastDelay: Option[FiniteDuration] = None): Either[E, T] =
    def nextAttemptOr(e: => Either[E, T]) =
      if remainingAttempts > 0 then
        val delay = policy.nextDelay(policy.maxRetries - remainingAttempts, lastDelay).toMillis
        if delay > 0 then Thread.sleep(delay)
        loop(remainingAttempts - 1, Some(delay.millis))
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
