package ox.scheduling

import org.scalatest.Inspectors
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration.*

class JitterTest extends AnyFlatSpec with Matchers:

  behavior of "Jitter"

  private val baseSchedule = Schedule.exponentialBackoff(100.millis)

  it should "use no jitter" in:
    // given
    val schedule = baseSchedule

    // when
    val delays = schedule.intervals().take(6).toList

    // then
    delays should contain theSameElementsInOrderAs Seq(100, 200, 400, 800, 1600, 3200).map(_.millis)

  it should "use full jitter" in:
    // given
    val schedule = baseSchedule.jitter(Jitter.Full)

    // when
    val rawDelays = baseSchedule.intervals().take(5).toList
    val delays = schedule.intervals().take(5).toList

    // then
    delays
      .zip(rawDelays)
      .foreach: (delay, backoffDelay) =>
        delay should (be >= 0.millis and be <= backoffDelay)

  it should "use equal jitter" in:
    // given
    val schedule = baseSchedule.jitter(Jitter.Equal)

    // when
    val rawDelays = baseSchedule.intervals().take(5).toList
    val delays = schedule.intervals().take(5).toList

    // then
    delays
      .zip(rawDelays)
      .foreach: (delay, backoffDelay) =>
        delay should (be >= backoffDelay / 2 and be <= backoffDelay)

  it should "use decorrelated jitter" in:
    // given
    val min = 100.millis
    val schedule = Schedule.decorrelatedJitter(min)

    // when
    val delays = schedule.intervals().take(5).toList

    // then
    Inspectors.forEvery(delays.sliding(2).toList):
      case Seq(previousDelay, delay) => delay should (be >= min and be <= previousDelay * 3)
      case _                         => fail("should never happen") // so that the match is exhaustive
end JitterTest
