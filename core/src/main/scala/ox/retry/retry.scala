package ox.retry

import scala.annotation.tailrec
import scala.concurrent.duration.*
import scala.util.Try

// overloaded variants for function
def retry[T](f: => T)(policy: RetryPolicy): T =
  retry(f, _ => true, _ => true)(policy)

def retry[T](f: => T, isSuccess: T => Boolean)(policy: RetryPolicy): T =
  retry(f, isSuccess, _ => true)(policy)

def retry[T](f: => T, isWorthRetrying: Throwable => Boolean)(policy: RetryPolicy)(using DummyImplicit): T =
  retry(f, _ => true, isWorthRetrying)(policy)

def retry[T](f: => T, isSuccess: T => Boolean, isWorthRetrying: Throwable => Boolean)(policy: RetryPolicy): T =
  retry(Try(f), isSuccess, isWorthRetrying)(policy).get

// overloaded variants for Try
def retry[T](f: => Try[T])(policy: RetryPolicy)(using dummy1: DummyImplicit, dummy2: DummyImplicit): Try[T] =
  retry(f, _ => true, _ => true)(policy)(using dummy1, dummy2)

def retry[T](f: => Try[T], isSuccess: T => Boolean)(policy: RetryPolicy)(using dummy1: DummyImplicit, dummy2: DummyImplicit): Try[T] =
  retry(f, isSuccess, _ => true)(policy)(using dummy1, dummy2)

def retry[T](f: => Try[T], isWorthRetrying: Throwable => Boolean)(
    policy: RetryPolicy
)(using dummy1: DummyImplicit, dummy2: DummyImplicit, dummy3: DummyImplicit): Try[T] =
  retry(f, _ => true, isWorthRetrying)(policy)(using dummy1, dummy2)

def retry[T](f: => Try[T], isSuccess: T => Boolean, isWorthRetrying: Throwable => Boolean)(
    policy: RetryPolicy
)(using dummy1: DummyImplicit, dummy2: DummyImplicit): Try[T] =
  retry(f.toEither, isSuccess, isWorthRetrying)(policy)(using dummy1).toTry

// overloaded variants for Either
def retry[E, T](f: => Either[E, T])(policy: RetryPolicy)(using dummy: DummyImplicit): Either[E, T] =
  retry(f, _ => true, _ => true)(policy)(using dummy)

def retry[E, T](f: => Either[E, T], isSuccess: T => Boolean)(policy: RetryPolicy)(using dummy: DummyImplicit): Either[E, T] =
  retry(f, isSuccess, _ => true)(policy)(using dummy)

def retry[E, T](f: => Either[E, T], isWorthRetrying: E => Boolean)(
    policy: RetryPolicy
)(using dummy1: DummyImplicit, dummy2: DummyImplicit): Either[E, T] =
  retry(f, _ => true, isWorthRetrying)(policy)(using dummy1)

def retry[E, T](f: => Either[E, T], isSuccess: T => Boolean, isWorthRetrying: E => Boolean)(policy: RetryPolicy)(using
    DummyImplicit
): Either[E, T] =
  @tailrec
  def loop(attempt: Int, remainingAttempts: Option[Int], lastDelay: Option[FiniteDuration]): Either[E, T] =
    def sleepIfNeeded =
      val delay = policy.nextDelay(attempt + 1, lastDelay).toMillis
      if (delay > 0) Thread.sleep(delay)
      delay

    f match
      case left @ Left(error) =>
        if isWorthRetrying(error) && remainingAttempts.forall(_ > 0) then
          val delay = sleepIfNeeded
          loop(attempt + 1, remainingAttempts.map(_ - 1), Some(delay.millis))
        else left
      case right @ Right(result) =>
        if !isSuccess(result) && remainingAttempts.forall(_ > 0) then
          val delay = sleepIfNeeded
          loop(attempt + 1, remainingAttempts.map(_ - 1), Some(delay.millis))
        else right

  val remainingAttempts = policy match
    case policy: RetryPolicy.Finite => Some(policy.maxRetries)
    case _                          => None

  loop(0, remainingAttempts, None)
