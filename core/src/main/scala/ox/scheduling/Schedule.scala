package ox.scheduling

import scala.concurrent.duration.*
import scala.util.Random

/** Describes a schedule according to which [[ox.resilience.retry]], [[repeat]] and [[schedule]] will invoke operations. A schedule is
  * essentially a list of intervals, which are used to determine how long to wait before subsequent invocations of the operation.
  *
  * Implementation note: the intervals lazy-list is lazy-evaluated itself, to avoid memory leaks when a schedule is captured as a value and
  * used multiple times. The intervals list is re-created on every usage, which isn't optimal, but due to the small size (and gradual
  * evaluation) of the list, should not cause any performance issues.
  *
  * @param intervals
  *   The intervals to use for the schedule.
  * @param initialDelay
  *   The delay to wait before running the operation for the first time - if any.
  */
case class Schedule(intervals: () => LazyList[FiniteDuration], initialDelay: Option[FiniteDuration] = None):
  /** Caps the intervals to the given maximum. */
  def maxInterval(max: FiniteDuration): Schedule = copy(intervals = () => intervals().map(_.min(max)))

  /** Caps the number of attempts to the given maximum, creating a finite schedule. The provided value specifies the total number of
    * invocations (attempts) of the operation, including the initial invocation.
    */
  def maxAttempts(max: Int): Schedule = copy(intervals = () => intervals().take(max - 1))

  /** Caps the number of retries to the given maximum, creating a finite schedule. The provided value specifies the number of retries after
    * the initial attempt. The total number of invocations will be `retries + 1`.
    */
  def maxRetries(retries: Int): Schedule = maxAttempts(retries + 1)

  /** Caps the total delay (cumulative time between attempts) to the given maximum. The resulting schedule might still be infinite, if the
    * intervals are originally 0.
    */
  def maxCumulativeDelay(upTo: FiniteDuration): Schedule = copy(intervals = () =>
    val d = intervals()
    d
      .scanLeft(0.seconds)((cumulative, next) => cumulative + next)
      .zip(d)
      .takeWhile(_._1 <= upTo)
      .map(_._2))

  /** Adds random jitter to each interval, using the provided jitter mode.
    *
    * The purpose of jitter is to avoid clustering of subsequent invocations, i.e. to reduce the number of clients calling a service exactly
    * at the same time - which can result in failures, contrary to what you would expect e.g. when retrying. By introducing randomness to
    * the delays, the repetitions become more evenly distributed over time.
    *
    * @see
    *   <a href="https://aws.amazon.com/blogs/architecture/exponential-backoff-and-jitter/">AWS Architecture Blog article on jitter</a>
    *
    * @see
    *   [[Schedule.decorrelatedJitter]]
    */
  def jitter(j: Jitter = Jitter.Equal): Schedule = copy(intervals =
    () =>
      intervals().map: d =>
        j match
          case Jitter.Full  => Random.between(0, d.toMillis).millis
          case Jitter.Equal =>
            val base = d.toMillis
            (base / 2 + Random.between(0, base / 2)).millis
  )

  /** Modifies the schedule so that operations are only run after the initial given delay. Later, the intervals specified by the schedule
    * are used.
    */
  def withInitialDelay(interval: FiniteDuration): Schedule = copy(initialDelay = Some(interval))

  /** Modifies the schedule so that the first invocation of the operation is run immediately, with subsequent invocations following the
    * intervals specified by the schedule.
    */
  def withNoInitialDelay(): Schedule = copy(initialDelay = None)

  /** Combines two schedules. The second schedule will only be used if the first one is finite. */
  def andThen(other: Schedule): Schedule = copy(intervals = () => intervals() ++ other.intervals())

  /** @see [[andThen]] */
  def ++(other: Schedule): Schedule = andThen(other)
end Schedule

object Schedule:
  /** An infinite schedule that immediately repeats the operation, without any delay. */
  val immediate: Schedule = fixedInterval(0.seconds)

  /** An infinite schedule that repeats the operation at the given fixed interval. */
  def fixedInterval(d: FiniteDuration): Schedule = Schedule(() => LazyList.continually(d))

  /** A finite schedule that repeats the operation at the given intervals, specified as a sequence of durations. */
  def intervals(intervals: FiniteDuration*): Schedule = Schedule(() => LazyList.from(intervals))

  /** An infinite, exponential backoff schedule with base 2. That is, each subsequent interval is twice as long as the previous one. */
  def exponentialBackoff(initial: FiniteDuration): Schedule = Schedule(() =>
    def loop(i: FiniteDuration): LazyList[FiniteDuration] = i #:: loop(capIntervalToMax(i * 2))
    loop(initial)
  )

  /** An infinite, Fibonacci backoff schedule. That is, each subsequent interval is the sum of the two previous intervals. */
  def fibonacciBackoff(initial: FiniteDuration): Schedule = Schedule(() =>
    def loop(i0: FiniteDuration, i1: FiniteDuration): LazyList[FiniteDuration] = i0 #:: loop(i1, capIntervalToMax(i0 + i1))
    loop(initial, initial)
  )

  /** An infinite, decorrelated jitter schedule, where each interval is a random duration between the given minimum and three times the
    * previous interval.
    *
    * @see
    *   <a href="https://aws.amazon.com/blogs/architecture/exponential-backoff-and-jitter/">AWS Architecture Blog article on jitter</a>
    */
  def decorrelatedJitter(min: FiniteDuration): Schedule = Schedule(() =>
    val minAsMillis = min.toMillis

    def loop(previous: Long): LazyList[FiniteDuration] =
      val next = Random.between(minAsMillis, previous * 3)
      val nextDuration = capIntervalToMax(next.millis)
      nextDuration #:: loop(nextDuration.toMillis)

    loop(minAsMillis)
  )

  /** An infinite computed schedule. Each interval is computed using the previously returned state (starting with the initial state). */
  def computed[S](initialState: S, compute: S => (S, Option[FiniteDuration])): Schedule = Schedule(() =>
    def loop(s: S): LazyList[FiniteDuration] =
      compute(s) match
        case (s2, Some(i)) => i #:: loop(s2)
        case (_, None)     => LazyList.empty[FiniteDuration] // no more intervals, stop the schedule

    loop(initialState)
  )

  private val intervalCap: FiniteDuration = (365 * 10).days // the maximum duration is ~292 years, capping way before we reach that
  /* Caps the given interval to a maximum, to avoid exceptions when trying to represent too large intervals; these are most likely capped
  by subsequent schedule transformations anyway - at least, they should be for the schedule to make sense. */
  private def capIntervalToMax(i: FiniteDuration): FiniteDuration = if i > intervalCap then intervalCap else i
end Schedule
