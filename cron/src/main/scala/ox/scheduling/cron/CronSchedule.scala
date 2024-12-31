package ox.scheduling.cron

import cron4s.lib.javatime.*
import cron4s.{Cron, CronExpr, toDateTimeCronOps}
import ox.scheduling.Schedule

import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.{Duration, FiniteDuration}

case class CronSchedule(cron: CronExpr) extends Schedule.Infinite:
  override def initialDelay: FiniteDuration =
    val now = LocalDateTime.now()
    val next = cron.next(now)
    val duration = next.map(n => ChronoUnit.MILLIS.between(now, n))
    duration.map(FiniteDuration.apply(_, TimeUnit.MILLISECONDS)).getOrElse(Duration.Zero)
  end initialDelay

  def nextDuration(invocation: Int, lastDuration: Option[FiniteDuration]): FiniteDuration =
    initialDelay
end CronSchedule

object CronSchedule:
  /** @param expression
    *   cron expression to parse
    * @return
    *   [[CronSchedule]] from cron expression
    * @throws cron4s.Error
    *   in case the cron expression is invalid
    */
  def unsafeFromString(expression: String): CronSchedule =
    CronSchedule(Cron.unsafeParse(expression))
end CronSchedule
