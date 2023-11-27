package ox.retry

import scala.concurrent.duration.*
import scala.util.Random

enum Jitter:
  case None, Full, Equal, Decorrelated

trait RetryPolicy:
  def nextDelay(attempt: Int, lastDelay: Option[FiniteDuration]): FiniteDuration

object RetryPolicy:

  trait Finite extends RetryPolicy:
    def maxRetries: Int

  trait Infinite extends RetryPolicy

  case class Direct(maxRetries: Int) extends Finite:
    override def nextDelay(attempt: Int, lastDelay: Option[FiniteDuration]): FiniteDuration = Duration.Zero

  object Direct:
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
      maxDelay: FiniteDuration = 1.day,
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
