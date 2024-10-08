package ox.scheduling

import org.scalatest.Inspectors
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration.*

class JitterTest extends AnyFlatSpec with Matchers:

  behavior of "Jitter"

  private val baseSchedule = Schedule.Backoff(maxRepeats = 3, firstDuration = 100.millis)

  it should "use no jitter" in {
    // given
    val schedule = baseSchedule

    // when
    val delays = (1 to 5).map(schedule.nextDuration(_, None))

    // then
    delays should contain theSameElementsInOrderAs Seq(200, 400, 800, 1600, 3200).map(_.millis)
  }

  it should "use full jitter" in {
    // given
    val schedule = baseSchedule.copy(jitter = Jitter.Full)

    // when
    val delays = (1 to 5).map(schedule.nextDuration(_, None))

    // then
    Inspectors.forEvery(delays.zipWithIndex) { case (delay, i) =>
      val backoffDelay = Schedule.Backoff.calculateDuration(i + 1, schedule.firstDuration, schedule.maxDuration)
      delay should (be >= 0.millis and be <= backoffDelay)
    }
  }

  it should "use equal jitter" in {
    // given
    val schedule = baseSchedule.copy(jitter = Jitter.Equal)

    // when
    val delays = (1 to 5).map(schedule.nextDuration(_, None))

    // then
    Inspectors.forEvery(delays.zipWithIndex) { case (delay, i) =>
      val backoffDelay = Schedule.Backoff.calculateDuration(i + 1, schedule.firstDuration, schedule.maxDuration)
      delay should (be >= backoffDelay / 2 and be <= backoffDelay)
    }
  }

  it should "use decorrelated jitter" in {
    // given
    val schedule = baseSchedule.copy(jitter = Jitter.Decorrelated)

    // when
    val delays = (1 to 5).map(schedule.nextDuration(_, None))

    // then
    Inspectors.forEvery(delays.sliding(2).toList) {
      case Seq(previousDelay, delay) =>
        delay should (be >= schedule.firstDuration and be <= previousDelay * 3)
      case _ => fail("should never happen") // so that the match is exhaustive
    }
  }
end JitterTest
