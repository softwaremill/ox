package ox.scheduling.cron

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import cron4s.*
import ox.scheduling.{RepeatConfig, repeat}
import scala.concurrent.duration.*
import ox.util.ElapsedTime

class CronScheduleTest extends AnyFlatSpec with Matchers with ElapsedTime:
  behavior of "repeat with cron schedule"

  it should "repeat a function every second" in {
    // given
    val cronExpr = Cron.unsafeParse("* * * ? * *") // every second
    val cronSchedule = CronSchedule.fromCronExpr(cronExpr)

    var counter = 0

    def f =
      if counter > 0 then throw new RuntimeException("boom")
      else counter += 1

    // when
    val (ex, elapsedTime) = measure(the[RuntimeException] thrownBy repeat(RepeatConfig(cronSchedule))(f))

    // then
    ex.getMessage shouldBe "boom"
    counter shouldBe 1
    elapsedTime.toMillis should be < 2200L // Run 2 times, so at most 2 secs - 200ms for tolerance
  }

  it should "provide initial delay" in {
    // give
    val cronExpr = Cron.unsafeParse("* * * ? * *") // every second
    val cronSchedule = CronSchedule.fromCronExpr(cronExpr)

    def f =
      throw new RuntimeException("boom")

    // when
    val (ex, elapsedTime) = measure(the[RuntimeException] thrownBy repeat(RepeatConfig(cronSchedule))(f))

    // then
    ex.getMessage shouldBe "boom"
    elapsedTime.toMillis should be < 1200L // Run 2 times, so at most 2 secs - 200ms for tolerance
    elapsedTime.toNanos should be > 0L
  }
end CronScheduleTest
