package ox.scheduling

import scala.concurrent.duration.*
import scala.util.Random

sealed trait Schedule:
  // TODO: consider better name that `attempt`
  def nextDelay(attempt: Int, lastDelay: Option[FiniteDuration], lastStartTimestamp: Option[Long]): FiniteDuration

object Schedule:

  private[scheduling] sealed trait Finite extends Schedule:
    def maxRepeats: Int
    def initialDelay: FiniteDuration = Duration.Zero
    def fallbackTo(fallback: Finite): Finite = FallingBack(this, fallback)
    def fallbackTo(fallback: Infinite): Infinite = FallingBack.forever(this, fallback)

  private[scheduling] sealed trait Infinite extends Schedule

  /** A schedule that represents an initial delay applied before the first invocation of operation being scheduled. Usually used in
    * combination with other schedules using [[fallbackTo]]
    *
    * @param delay
    *   The initial delay.
    * @example
    *   {{{
    *   Schedule.InitialDelay(1.second).fallbackTo(Schedule.Delay.forever(100.millis))
    *   }}}
    */
  case class InitialDelay private[scheduling] (delay: FiniteDuration) extends Finite:
    override def maxRepeats: Int = 0
    override def nextDelay(attempt: Int, lastDelay: Option[FiniteDuration], lastStartTimestamp: Option[Long]): FiniteDuration =
      Duration.Zero
    override def initialDelay: FiniteDuration = delay

  /** A schedule that repeats up to a given number of times, with no delay between subsequent repeats.
    *
    * @param maxRepeats
    *   The maximum number of repeats.
    */
  case class Immediate(maxRepeats: Int) extends Finite:
    override def nextDelay(attempt: Int, lastDelay: Option[FiniteDuration], lastStartTimestamp: Option[Long]): FiniteDuration =
      Duration.Zero

  object Immediate:
    /** A schedule that repeats indefinitely, with no delay between subsequent repeats. */
    def forever: Infinite = ImmediateForever

  private case object ImmediateForever extends Infinite:
    override def nextDelay(attempt: Int, lastDelay: Option[FiniteDuration], lastStartTimestamp: Option[Long]): FiniteDuration =
      Duration.Zero

  /** A schedule that repeats up to a given number of times, with a fixed delay between subsequent repeats.
    *
    * @param maxRepeats
    *   The maximum number of repeats.
    * @param delay
    *   The delay between subsequent repeats.
    */
  case class Delay(maxRepeats: Int, delay: FiniteDuration) extends Finite:
    override def nextDelay(attempt: Int, lastDelay: Option[FiniteDuration], lastStartTimestamp: Option[Long]): FiniteDuration = delay

  object Delay:
    /** A schedule that repeats indefinitely, with a fixed delay between subsequent repeats.
      *
      * @param delay
      *   The delay between subsequent repeats.
      */
    def forever(delay: FiniteDuration): Infinite = DelayForever(delay)

  private[scheduling] case class DelayForever(delay: FiniteDuration) extends Infinite:
    override def nextDelay(attempt: Int, lastDelay: Option[FiniteDuration], lastStartTimestamp: Option[Long]): FiniteDuration = delay

  /** A schedule that repeats up to a given number of times, with a fixed rate between subsequent repeats. If the previous operation took
    * longer than the interval, the next operation will be scheduled immediately.
    *
    * @param maxRepeats
    *   The maximum number of repeats.
    * @param interval
    *   The interval between subsequent operations.
    */
  case class FixedRate(maxRepeats: Int, interval: FiniteDuration) extends Finite:
    override def nextDelay(attempt: Int, lastDelay: Option[FiniteDuration], lastStartTimestamp: Option[Long]): FiniteDuration =
      FixedRate.nextDelay(interval, lastStartTimestamp)

  object FixedRate:
    /** A schedule that repeats indefinitely, with a fixed rate between subsequent repeats. If the previous operation took longer than the
      * interval, the next operation will be scheduled immediately.
      *
      * @param interval
      *   The interval between subsequent operations.
      */
    def forever(interval: FiniteDuration): Infinite = FixedRateForever(interval)

    private[scheduling] def nextDelay(interval: FiniteDuration, lastStartTimestamp: Option[Long]) =
      lastStartTimestamp match
        case Some(startTimestamp) =>
          val elapsed = System.nanoTime() - startTimestamp
          val remaining = interval.toNanos - elapsed
          if remaining > 0 then remaining.nanos
          else Duration.Zero
        case None => interval

  private[scheduling] case class FixedRateForever(interval: FiniteDuration) extends Infinite:
    override def nextDelay(attempt: Int, lastDelay: Option[FiniteDuration], lastStartTimestamp: Option[Long]): FiniteDuration =
      FixedRate.nextDelay(interval, lastStartTimestamp)

  /** A schedule that repeats up to a given number of times, with an exponentially increasing delay between subsequent repeats.
    *
    * The delay is exponential with base 2 (i.e. the next delay is twice as long as the previous one), starting at the given initial delay
    * and capped at the given maximum delay.
    *
    * @param maxRepeats
    *   The maximum number of repeats.
    * @param firstDelay
    *   The delay before the first repeat.
    * @param maxDelay
    *   The maximum delay between subsequent repeats.
    * @param jitter
    *   A random factor used for calculating the delay between subsequent repeats. See [[Jitter]] for more details. Defaults to no jitter,
    *   i.e. an exponential backoff with no adjustments.
    */
  case class Exponential(
      maxRepeats: Int,
      firstDelay: FiniteDuration,
      maxDelay: FiniteDuration = 1.minute,
      jitter: Jitter = Jitter.None
  ) extends Finite:
    override def nextDelay(attempt: Int, lastDelay: Option[FiniteDuration], lastStartTimestamp: Option[Long]): FiniteDuration =
      Exponential.nextDelay(attempt, firstDelay, maxDelay, jitter, lastDelay)

  object Exponential:
    // TODO: restore the private modifier
    def delay(attempt: Int, firstDelay: FiniteDuration, maxDelay: FiniteDuration): FiniteDuration =
      // converting Duration <-> Long back and forth to avoid exceeding maximum duration
      (firstDelay.toMillis * Math.pow(2, attempt)).toLong.min(maxDelay.toMillis).millis

    private[scheduling] def nextDelay(
        attempt: Int,
        firstDelay: FiniteDuration,
        maxDelay: FiniteDuration,
        jitter: Jitter,
        lastDelay: Option[FiniteDuration]
    ): FiniteDuration =
      def exponentialDelay = Exponential.delay(attempt, firstDelay, maxDelay)

      jitter match
        case Jitter.None => exponentialDelay
        case Jitter.Full => Random.between(0, exponentialDelay.toMillis).millis
        case Jitter.Equal =>
          val backoff = exponentialDelay.toMillis
          (backoff / 2 + Random.between(0, backoff / 2)).millis
        case Jitter.Decorrelated =>
          val last = lastDelay.getOrElse(firstDelay).toMillis
          Random.between(firstDelay.toMillis, last * 3).millis

    /** A schedule that repeats indefinitely, with an exponentially increasing delay between subsequent repeats.
      *
      * The delay is exponential with base 2 (i.e. the next delay is twice as long as the previous one), starting at the given initial delay
      * and capped at the given maximum delay.
      *
      * @param firstDelay
      *   The delay before the first repeat.
      * @param maxDelay
      *   The maximum delay between subsequent repeats.
      * @param jitter
      *   A random factor used for calculating the delay between subsequent repeats. See [[Jitter]] for more details. Defaults to no jitter,
      *   i.e. an exponential backoff with no adjustments.
      */
    def forever(firstDelay: FiniteDuration, maxDelay: FiniteDuration = 1.minute, jitter: Jitter = Jitter.None): Infinite =
      ExponentialForever(firstDelay, maxDelay, jitter)

  case class ExponentialForever private[scheduling] (
      firstDelay: FiniteDuration,
      maxDelay: FiniteDuration = 1.minute,
      jitter: Jitter = Jitter.None
  ) extends Infinite:
    override def nextDelay(attempt: Int, lastDelay: Option[FiniteDuration], lastStartTimestamp: Option[Long]): FiniteDuration =
      Exponential.nextDelay(attempt, firstDelay, maxDelay, jitter, lastDelay)

  private[scheduling] sealed trait WithFallback extends Schedule:
    def base: Finite
    def fallback: Schedule
    override def nextDelay(attempt: Int, lastDelay: Option[FiniteDuration], lastStartTimestamp: Option[Long]): FiniteDuration =
      if base.maxRepeats > attempt then base.nextDelay(attempt, lastDelay, lastStartTimestamp)
      else fallback.nextDelay(attempt - base.maxRepeats, lastDelay, lastStartTimestamp)

  /** A schedule that combines two schedules, using [[base]] first [[base.maxRepeats]] number of times, and then using [[fallback]]
    * [[fallback.maxRepeats]] number of times.
    */
  case class FallingBack(base: Finite, fallback: Finite) extends WithFallback, Finite:
    override def maxRepeats: Int = base.maxRepeats + fallback.maxRepeats
    override def initialDelay: FiniteDuration = base.initialDelay

  object FallingBack:
    /** A schedule that repeats indefinitely, using [[base]] first [[base.maxRepeats]] number of times, and then always using [[fallback]].
      */
    def forever(base: Finite, fallback: Infinite): Infinite = FallingBackForever(base, fallback)

  case class FallingBackForever private[scheduling] (base: Finite, fallback: Infinite) extends WithFallback, Infinite
