package ox.retry

import scala.concurrent.duration.*
import scala.util.Random

case class RetryPolicy[E, T](schedule: Schedule, resultPolicy: ResultPolicy[E, T] = ResultPolicy.default[E, T])

object RetryPolicy:
  def immediate[E, T](maxRetries: Int): RetryPolicy[E, T] = RetryPolicy(Schedule.Immediate(maxRetries))
  def immediateForever[E, T]: RetryPolicy[E, T] = RetryPolicy(Schedule.Immediate.forever)

  def delay[E, T](maxRetries: Int, delay: FiniteDuration): RetryPolicy[E, T] = RetryPolicy(Schedule.Delay(maxRetries, delay))
  def delayForever[E, T](delay: FiniteDuration): RetryPolicy[E, T] = RetryPolicy(Schedule.Delay.forever(delay))

  def backoff[E, T](
      maxRetries: Int,
      initialDelay: FiniteDuration,
      maxDelay: FiniteDuration = 1.minute,
      jitter: Jitter = Jitter.None
  ): RetryPolicy[E, T] =
    RetryPolicy(Schedule.Backoff(maxRetries, initialDelay, maxDelay, jitter))

  def backoffForever[E, T](
      initialDelay: FiniteDuration,
      maxDelay: FiniteDuration = 1.minute,
      jitter: Jitter = Jitter.None
  ): RetryPolicy[E, T] =
    RetryPolicy(Schedule.Backoff.forever(initialDelay, maxDelay, jitter))

enum Jitter:
  case None, Full, Equal, Decorrelated

sealed trait Schedule:
  def nextDelay(attempt: Int, lastDelay: Option[FiniteDuration]): FiniteDuration

object Schedule:

  sealed trait Finite extends Schedule:
    def maxRetries: Int

  sealed trait Infinite extends Schedule

  case class Immediate(maxRetries: Int) extends Finite:
    override def nextDelay(attempt: Int, lastDelay: Option[FiniteDuration]): FiniteDuration = Duration.Zero

  object Immediate:
    def forever: Infinite = DirectForever

  case object DirectForever extends Infinite:
    override def nextDelay(attempt: Int, lastDelay: Option[FiniteDuration]): FiniteDuration = Duration.Zero

  case class Delay(maxRetries: Int, delay: FiniteDuration) extends Finite:
    override def nextDelay(attempt: Int, lastDelay: Option[FiniteDuration]): FiniteDuration = delay

  object Delay:
    def forever(delay: FiniteDuration): Infinite = DelayForever(delay)

  private case class DelayForever(delay: FiniteDuration) extends Infinite:
    override def nextDelay(attempt: Int, lastDelay: Option[FiniteDuration]): FiniteDuration = delay

  case class Backoff(
      maxRetries: Int,
      initialDelay: FiniteDuration,
      maxDelay: FiniteDuration = 1.minute,
      jitter: Jitter = Jitter.None
  ) extends Finite:
    override def nextDelay(attempt: Int, lastDelay: Option[FiniteDuration]): FiniteDuration =
      Backoff.nextDelay(attempt, initialDelay, maxDelay, jitter, lastDelay)

  object Backoff:
    private[retry] def delay(attempt: Int, initialDelay: FiniteDuration, maxDelay: FiniteDuration): FiniteDuration =
      // converting Duration <-> Long back and forth to avoid exceeding maximum duration
      (initialDelay.toMillis * Math.pow(2, attempt)).toLong.min(maxDelay.toMillis).millis

    private[retry] def nextDelay(
        attempt: Int,
        initialDelay: FiniteDuration,
        maxDelay: FiniteDuration,
        jitter: Jitter,
        lastDelay: Option[FiniteDuration]
    ): FiniteDuration =
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

    def forever(initialDelay: FiniteDuration, maxDelay: FiniteDuration = 1.day, jitter: Jitter = Jitter.None): Infinite =
      BackoffForever(initialDelay, maxDelay, jitter)

  private case class BackoffForever(initialDelay: FiniteDuration, maxDelay: FiniteDuration = 1.day, jitter: Jitter = Jitter.None)
      extends Infinite:
    override def nextDelay(attempt: Int, lastDelay: Option[FiniteDuration]): FiniteDuration =
      Backoff.nextDelay(attempt, initialDelay, maxDelay, jitter, lastDelay)

case class ResultPolicy[E, T](isSuccess: T => Boolean = (_: T) => true, isWorthRetrying: E => Boolean = (_: E) => true)

object ResultPolicy:
  def default[E, T]: ResultPolicy[E, T] = ResultPolicy()

  def successfulWhen[E, T](f: T => Boolean): ResultPolicy[E, T] = ResultPolicy(isSuccess = f)

  def retryWhen[E, T](f: E => Boolean): ResultPolicy[E, T] = ResultPolicy(isWorthRetrying = f)

  def neverRetry[E, T]: ResultPolicy[E, T] = ResultPolicy(isWorthRetrying = _ => false)
