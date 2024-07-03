package ox.scheduling

import scala.concurrent.duration.*
import scala.util.Random

sealed trait Schedule:
  def nextDelay(attempt: Int, lastDelay: Option[FiniteDuration], lastStartTimestamp: Option[Long]): FiniteDuration

object Schedule:

  private[scheduling] sealed trait Finite extends Schedule:
    def maxRetries: Int
    def fallbackTo(fallback: Finite): Finite = FallingBack(this, fallback)
    def fallbackTo(fallback: Infinite): Infinite = FallingBack.forever(this, fallback)

  private[scheduling] sealed trait Infinite extends Schedule

  /** A schedule that retries up to a given number of times, with no delay between subsequent attempts.
    *
    * @param maxRetries
    *   The maximum number of retries.
    */
  case class Immediate(maxRetries: Int) extends Finite:
    override def nextDelay(attempt: Int, lastDelay: Option[FiniteDuration], lastStartTimestamp: Option[Long]): FiniteDuration =
      Duration.Zero

  object Immediate:
    /** A schedule that retries indefinitely, with no delay between subsequent attempts. */
    def forever: Infinite = ImmediateForever

  private case object ImmediateForever extends Infinite:
    override def nextDelay(attempt: Int, lastDelay: Option[FiniteDuration], lastStartTimestamp: Option[Long]): FiniteDuration =
      Duration.Zero

  /** A schedule that retries up to a given number of times, with a fixed delay between subsequent attempts.
    *
    * @param maxRetries
    *   The maximum number of retries.
    * @param delay
    *   The delay between subsequent attempts.
    */
  case class Delay(maxRetries: Int, delay: FiniteDuration) extends Finite:
    override def nextDelay(attempt: Int, lastDelay: Option[FiniteDuration], lastStartTimestamp: Option[Long]): FiniteDuration = delay

  object Delay:
    /** A schedule that retries indefinitely, with a fixed delay between subsequent attempts.
      *
      * @param delay
      *   The delay between subsequent attempts.
      */
    def forever(delay: FiniteDuration): Infinite = DelayForever(delay)

  case class DelayForever private[scheduling] (delay: FiniteDuration) extends Infinite:
    override def nextDelay(attempt: Int, lastDelay: Option[FiniteDuration], lastStartTimestamp: Option[Long]): FiniteDuration = delay

  // TODO: doc
  // TODO: infinite variant
  case class FixedRate(maxRetries: Int, duration: FiniteDuration) extends Finite:
    override def nextDelay(attempt: Int, lastDelay: Option[FiniteDuration], lastStartTimestamp: Option[Long]): FiniteDuration =
      lastStartTimestamp match
        case Some(startTimestamp) =>
          val elapsed = System.nanoTime() - startTimestamp
          val remaining = duration.toNanos - elapsed
          if remaining > 0 then remaining.nanos
          else Duration.Zero
        case None => duration

  /** A schedule that retries up to a given number of times, with an increasing delay (backoff) between subsequent attempts.
    *
    * The backoff is exponential with base 2 (i.e. the next delay is twice as long as the previous one), starting at the given initial delay
    * and capped at the given maximum delay.
    *
    * @param maxRetries
    *   The maximum number of retries.
    * @param initialDelay
    *   The delay before the first retry.
    * @param maxDelay
    *   The maximum delay between subsequent retries.
    * @param jitter
    *   A random factor used for calculating the delay between subsequent retries. See [[Jitter]] for more details. Defaults to no jitter,
    *   i.e. an exponential backoff with no adjustments.
    */
  case class Backoff(
      maxRetries: Int,
      initialDelay: FiniteDuration,
      maxDelay: FiniteDuration = 1.minute,
      jitter: Jitter = Jitter.None
  ) extends Finite:
    override def nextDelay(attempt: Int, lastDelay: Option[FiniteDuration], lastStartTimestamp: Option[Long]): FiniteDuration =
      Backoff.nextDelay(attempt, initialDelay, maxDelay, jitter, lastDelay)

  object Backoff:
    // TODO: restore the private modifier
    def delay(attempt: Int, initialDelay: FiniteDuration, maxDelay: FiniteDuration): FiniteDuration =
      // converting Duration <-> Long back and forth to avoid exceeding maximum duration
      (initialDelay.toMillis * Math.pow(2, attempt)).toLong.min(maxDelay.toMillis).millis

    private[scheduling] def nextDelay(
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

    /** A schedule that retries indefinitely, with an increasing delay (backoff) between subsequent attempts.
      *
      * The backoff is exponential with base 2 (i.e. the next delay is twice as long as the previous one), starting at the given initial
      * delay and capped at the given maximum delay.
      *
      * @param initialDelay
      *   The delay before the first retry.
      * @param maxDelay
      *   The maximum delay between subsequent retries.
      * @param jitter
      *   A random factor used for calculating the delay between subsequent retries. See [[Jitter]] for more details. Defaults to no jitter,
      *   i.e. an exponential backoff with no adjustments.
      */
    def forever(initialDelay: FiniteDuration, maxDelay: FiniteDuration = 1.minute, jitter: Jitter = Jitter.None): Infinite =
      BackoffForever(initialDelay, maxDelay, jitter)

  case class BackoffForever private[scheduling] (
      initialDelay: FiniteDuration,
      maxDelay: FiniteDuration = 1.minute,
      jitter: Jitter = Jitter.None
  ) extends Infinite:
    override def nextDelay(attempt: Int, lastDelay: Option[FiniteDuration], lastStartTimestamp: Option[Long]): FiniteDuration =
      Backoff.nextDelay(attempt, initialDelay, maxDelay, jitter, lastDelay)

  private[scheduling] sealed trait WithFallback extends Schedule:
    def base: Finite
    def fallback: Schedule
    override def nextDelay(attempt: Int, lastDelay: Option[FiniteDuration], lastStartTimestamp: Option[Long]): FiniteDuration =
      if base.maxRetries > attempt then base.nextDelay(attempt, lastDelay, lastStartTimestamp)
      else fallback.nextDelay(attempt - base.maxRetries, lastDelay, lastStartTimestamp)

  /** A schedule that combines two schedules, using [[base]] first [[base.maxRetries]] number of times, and then using [[fallback]]
    * [[fallback.maxRetries]] number of times.
    */
  case class FallingBack(base: Finite, fallback: Finite) extends WithFallback, Finite:
    override def maxRetries: Int = base.maxRetries + fallback.maxRetries

  object FallingBack:
    /** A schedule that retries indefinitely, using [[base]] first [[base.maxRetries]] number of times, and then always using [[fallback]].
      */
    def forever(base: Finite, fallback: Infinite): Infinite = FallingBackForever(base, fallback)

  case class FallingBackForever private[scheduling] (base: Finite, fallback: Infinite) extends WithFallback, Infinite
