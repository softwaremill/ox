package ox.scheduling

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.{EitherValues, TryValues}
import ox.{ElapsedTime, sleep}

import scala.concurrent.duration.*

class RepeatTest extends AnyFlatSpec with Matchers with EitherValues with TryValues with ElapsedTime:

  behavior of "scheduleOp"

  it should "repeat a function at fixed rate" in {
    // given
    val repeats = 3
    val funcSleepTime = 30.millis
    val interval = 100.millis
    var counter = 0
    def f =
      counter += 1
      sleep(funcSleepTime)
      counter

    // when
    val (result, elapsedTime) = measure(repeat(RepeatConfig.fixedRate(repeats, interval))(f))

    // then
    elapsedTime.toMillis should be >= 3 * interval.toMillis + funcSleepTime.toMillis - 5 // tolerance
    elapsedTime.toMillis should be < 4 * interval.toMillis
    result shouldBe 4
    counter shouldBe 4
  }

  it should "repeat a function with initial delay" in {
    // given
    val repeats = 3
    val initialDelay = 50.millis
    val delay = 100.millis
    var counter = 0

    def f =
      counter += 1
      counter

    val schedule = Schedule.InitialDelay(initialDelay).fallbackTo(Schedule.Delay(repeats, delay))

    // when
    val (result, elapsedTime) = measure(repeat(RepeatConfig[Throwable, Int](schedule))(f))

    // then
    elapsedTime.toMillis should be >= 3 * delay.toMillis + initialDelay.toMillis - 5 // tolerance
    elapsedTime.toMillis should be < 4 * delay.toMillis
    result shouldBe 4
    counter shouldBe 4
  }
