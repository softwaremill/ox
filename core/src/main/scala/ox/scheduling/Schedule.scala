package ox.scheduling

import scala.concurrent.duration.*
import scala.util.Random

sealed trait Schedule:
  def nextDuration(invocation: Int, lastDuration: Option[FiniteDuration]): FiniteDuration
  def initialDelay: FiniteDuration = Duration.Zero

object Schedule:

  private[scheduling] sealed trait Finite extends Schedule:
    def maxRepeats: Int
    def andThen(nextSchedule: Finite): Finite = FiniteAndFiniteSchedules(this, nextSchedule)
    def andThen(nextSchedule: Infinite): Infinite = FiniteAndInfiniteSchedules(this, nextSchedule)

  private[scheduling] sealed trait Infinite extends Schedule

  /** A schedule that represents an initial delay applied before the first invocation of operation being scheduled. Usually used in
    * combination with other schedules using [[andThen]]
    *
    * @param delay
    *   The initial delay.
    * @example
    *   {{{
    *   Schedule.InitialDelay(1.second).andThen(Schedule.Delay.forever(100.millis))
    *   }}}
    */
  case class InitialDelay(delay: FiniteDuration) extends Finite:
    override def maxRepeats: Int = 0
    override def nextDuration(invocation: Int, lastDuration: Option[FiniteDuration]): FiniteDuration =
      Duration.Zero
    override def initialDelay: FiniteDuration = delay

  /** A schedule that represents an immediate invocation, up to a given number of times.
    *
    * @param maxRepeats
    *   The maximum number of invocations.
    */
  case class Immediate(maxRepeats: Int) extends Finite:
    override def nextDuration(invocation: Int, lastDuration: Option[FiniteDuration]): FiniteDuration =
      Duration.Zero

  object Immediate:
    /** A schedule that represents an immediate invocation without any invocations limit */
    def forever: Infinite = ImmediateForever

  private case object ImmediateForever extends Infinite:
    override def nextDuration(invocation: Int, lastDuration: Option[FiniteDuration]): FiniteDuration =
      Duration.Zero

  /** A schedule that represents a fixed duration between invocations, up to a given number of times.
    *
    * @param maxRepeats
    *   The maximum number of repeats.
    * @param duration
    *   The duration between subsequent invocations.
    */
  case class Fixed(maxRepeats: Int, duration: FiniteDuration) extends Finite:
    override def nextDuration(invocation: Int, lastDuration: Option[FiniteDuration]): FiniteDuration = duration

  object Fixed:
    /** A schedule that represents a fixed duration between invocations without any invocations limit.
      *
      * @param duration
      *   The duration between subsequent invocations.
      */
    def forever(duration: FiniteDuration): Infinite = FixedForever(duration)

  private[scheduling] case class FixedForever(duration: FiniteDuration) extends Infinite:
    override def nextDuration(invocation: Int, lastDuration: Option[FiniteDuration]): FiniteDuration = duration

  /** A schedule that represents an increasing duration between invocations (backoff), up to a given number of times.
    *
    * The backoff is exponential with base 2 (i.e. the next duration is twice as long as the previous one), starting at the given first
    * duration and capped at the given maximum duration.
    *
    * @param maxRepeats
    *   The maximum number of repeats.
    * @param firstDuration
    *   The duration between the first and the second invocations.
    * @param maxDuration
    *   The maximum duration between subsequent invocations.
    * @param jitter
    *   A random factor used for calculating the duration between subsequent repeats. See [[Jitter]] for more details. Defaults to no
    *   jitter, i.e. an exponential backoff with no adjustments.
    */
  case class Backoff(
      maxRepeats: Int,
      firstDuration: FiniteDuration,
      maxDuration: FiniteDuration = 1.minute,
      jitter: Jitter = Jitter.None
  ) extends Finite:
    override def nextDuration(invocation: Int, lastDuration: Option[FiniteDuration]): FiniteDuration =
      Backoff.nextDuration(invocation, firstDuration, maxDuration, jitter, lastDuration)
  end Backoff

  object Backoff:
    private[scheduling] def calculateDuration(invocation: Int, firstDuration: FiniteDuration, maxDuration: FiniteDuration): FiniteDuration =
      // converting Duration <-> Long back and forth to avoid exceeding maximum duration
      (firstDuration.toMillis * Math.pow(2, invocation)).toLong.min(maxDuration.toMillis).millis

    private[scheduling] def nextDuration(
        invocation: Int,
        firstDuration: FiniteDuration,
        maxDuration: FiniteDuration,
        jitter: Jitter,
        lastDuration: Option[FiniteDuration]
    ): FiniteDuration =
      def exponentialDuration = Backoff.calculateDuration(invocation, firstDuration, maxDuration)

      jitter match
        case Jitter.None => exponentialDuration
        case Jitter.Full => Random.between(0, exponentialDuration.toMillis).millis
        case Jitter.Equal =>
          val backoff = exponentialDuration.toMillis
          (backoff / 2 + Random.between(0, backoff / 2)).millis
        case Jitter.Decorrelated =>
          val last = lastDuration.getOrElse(firstDuration).toMillis
          Random.between(firstDuration.toMillis, last * 3).millis
      end match
    end nextDuration

    /** A schedule that represents an increasing duration between invocations (backoff) without any invocations limit.
      *
      * The backoff is exponential with base 2 (i.e. the next duration is twice as long as the previous one), starting at the given first
      * duration and capped at the given maximum duration.
      *
      * @param firstDuration
      *   The duration between the first and the second invocations.
      * @param maxDuration
      *   The maximum duration between subsequent repeats.
      * @param jitter
      *   A random factor used for calculating the duration between subsequent repeats. See [[Jitter]] for more details. Defaults to no
      *   jitter, i.e. an exponential backoff with no adjustments.
      */
    def forever(firstDuration: FiniteDuration, maxDuration: FiniteDuration = 1.minute, jitter: Jitter = Jitter.None): Infinite =
      BackoffForever(firstDuration, maxDuration, jitter)
  end Backoff

  private[scheduling] case class BackoffForever(
      firstDuration: FiniteDuration,
      maxDuration: FiniteDuration = 1.minute,
      jitter: Jitter = Jitter.None
  ) extends Infinite:
    override def nextDuration(invocation: Int, lastDuration: Option[FiniteDuration]): FiniteDuration =
      Backoff.nextDuration(invocation, firstDuration, maxDuration, jitter, lastDuration)

  private[scheduling] sealed trait CombinedSchedules extends Schedule:
    def first: Finite
    def second: Schedule
    override def nextDuration(invocation: Int, lastDuration: Option[FiniteDuration]): FiniteDuration =
      if first.maxRepeats > invocation then first.nextDuration(invocation, lastDuration)
      else second.nextDuration(invocation - first.maxRepeats, lastDuration)

  /** A schedule that combines two schedules, using [[first]] first [[first.maxRepeats]] number of times, and then using [[second]]
    * [[second.maxRepeats]] number of times.
    */
  private[scheduling] case class FiniteAndFiniteSchedules(first: Finite, second: Finite) extends CombinedSchedules, Finite:
    override def maxRepeats: Int = first.maxRepeats + second.maxRepeats
    override def initialDelay: FiniteDuration = first.initialDelay

  /** A schedule that combines two schedules, using [[first]] first [[first.maxRepeats]] number of times, and then always using [[second]].
    */
  private[scheduling] case class FiniteAndInfiniteSchedules(first: Finite, second: Infinite) extends CombinedSchedules, Infinite:
    override def initialDelay: FiniteDuration = first.initialDelay
end Schedule
