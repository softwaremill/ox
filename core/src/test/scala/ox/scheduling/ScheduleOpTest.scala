package ox.scheduling

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.{EitherValues, TryValues}
import ox.{ElapsedTime, sleep}
import ox.resilience.*

import scala.concurrent.duration.*

class ScheduleOpTest extends AnyFlatSpec with Matchers with EitherValues with TryValues with ElapsedTime:

  behavior of "scheduleOp"

  it should "schedule a function to run every n ms" in {
    // given
    val maxRetries = 3
    val funcSleepTime = 75.millis
    val scheduleSleepTime = 100.millis
    var counter = 0
    def f =
      counter += 1
      sleep(funcSleepTime)
      counter

    // when
    val (result, elapsedTime) = measure(schedule(Schedule.Every(maxRetries, scheduleSleepTime))(f))

    // then
    elapsedTime.toMillis should be >= 3 * scheduleSleepTime.toMillis + funcSleepTime.toMillis
    elapsedTime.toMillis should be < 4 * scheduleSleepTime.toMillis
    result shouldBe 4
    counter shouldBe 4
  }

  it should "schedule a function to run with delay" in {
    // given
    val maxRetries = 3
    val funcSleepTime = 20.millis
    val scheduleSleepTime = 100.millis
    var counter = 0

    def f =
      counter += 1
      sleep(funcSleepTime)
      counter

    // when
    val (result, elapsedTime) = measure(schedule(Schedule.Delay(maxRetries, scheduleSleepTime))(f))

    // then
    elapsedTime.toMillis should be >= 3 * (scheduleSleepTime.toMillis + funcSleepTime.toMillis) + funcSleepTime.toMillis
    result shouldBe 4
    counter shouldBe 4
  }
