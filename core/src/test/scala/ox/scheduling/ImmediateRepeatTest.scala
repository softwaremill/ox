package ox.scheduling

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.{EitherValues, TryValues}
import ox.{ElapsedTime, sleep}

import scala.concurrent.duration.*

class ImmediateRepeatTest extends AnyFlatSpec with Matchers with EitherValues with TryValues with ElapsedTime:

  behavior of "repeat"

  it should "repeat a function immediately" in {
    // given
    val repeats = 3
    var counter = 0
    def f =
      counter += 1
      counter

    // when
    val (result, elapsedTime) = measure(repeat(RepeatConfig.immediate(repeats))(f))

    // then
    elapsedTime.toMillis should be < 20L
    result shouldBe 4
    counter shouldBe 4
  }

  it should "repeat a function immediately with initial delay" in {
    // given
    val repeats = 3
    val initialDelay = 50.millis
    var counter = 0

    def f =
      counter += 1
      counter

    // when
    val (result, elapsedTime) = measure(repeat(RepeatConfig.immediate(repeats, Some(initialDelay)))(f))

    // then
    elapsedTime.toMillis should be >= initialDelay.toMillis
    elapsedTime.toMillis should be < initialDelay.toMillis + 20
    result shouldBe 4
    counter shouldBe 4
  }

  it should "repeat a function immediately forever" in {
    // given
    var counter = 0

    def f =
      counter += 1
      if counter == 4 then throw new RuntimeException("boom")
      counter

    // when
    val (ex, elapsedTime) = measure(the[RuntimeException] thrownBy repeat(RepeatConfig.immediateForever())(f))

    // then
    elapsedTime.toMillis should be < 20L
    ex.getMessage shouldBe "boom"
    counter shouldBe 4
  }

  it should "repeat a function immediately forever with initial delay" in {
    // given
    val initialDelay = 50.millis
    var counter = 0

    def f =
      counter += 1
      if counter == 4 then throw new RuntimeException("boom")
      counter

    // when
    val (ex, elapsedTime) = measure(the[RuntimeException] thrownBy repeat(RepeatConfig.immediateForever(Some(initialDelay)))(f))

    // then
    elapsedTime.toMillis should be >= initialDelay.toMillis
    elapsedTime.toMillis should be < initialDelay.toMillis + 20
    ex.getMessage shouldBe "boom"
    counter shouldBe 4
  }
