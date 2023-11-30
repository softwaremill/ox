package ox.retry

import scala.annotation.tailrec
import scala.concurrent.duration.*
import scala.util.Try

def retry[T](f: => T)(policy: RetryPolicy[Throwable, T]): T =
  retry(Try(f))(policy).get

def retry[T](f: => Try[T])(policy: RetryPolicy[Throwable, T])(using DummyImplicit): Try[T] =
  retry(f.toEither)(policy).toTry

def retry[E, T](f: => Either[E, T])(policy: RetryPolicy[E, T])(using dummy1: DummyImplicit, dummy2: DummyImplicit): Either[E, T] =
  @tailrec
  def loop(attempt: Int, remainingAttempts: Option[Int], lastDelay: Option[FiniteDuration]): Either[E, T] =
    def sleepIfNeeded =
      val delay = policy.schedule.nextDelay(attempt + 1, lastDelay).toMillis
      if (delay > 0) Thread.sleep(delay)
      delay

    f match
      case left @ Left(error) =>
        if policy.resultPolicy.isWorthRetrying(error) && remainingAttempts.forall(_ > 0) then
          val delay = sleepIfNeeded
          loop(attempt + 1, remainingAttempts.map(_ - 1), Some(delay.millis))
        else left
      case right @ Right(result) =>
        if !policy.resultPolicy.isSuccess(result) && remainingAttempts.forall(_ > 0) then
          val delay = sleepIfNeeded
          loop(attempt + 1, remainingAttempts.map(_ - 1), Some(delay.millis))
        else right

  val remainingAttempts = policy.schedule match
    case policy: Schedule.Finite => Some(policy.maxRetries)
    case _                       => None

  loop(0, remainingAttempts, None)
