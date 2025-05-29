package ox.scheduling.cron

import cron4s.lib.javatime.*
import cron4s.{Cron, CronExpr, toDateTimeCronOps}
import ox.scheduling.Schedule

import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import scala.concurrent.duration.*

/** Methods in this object provide [[Schedule]] based on supplied cron expression.
  */
object CronSchedule:
  /** @param expression
    *   cron expression to parse
    * @return
    *   [[CronSchedule]] from cron expression
    * @throws cron4s.Error
    *   in case of invalid expression
    */
  def unsafeFromString(expression: String): Schedule =
    fromCronExpr(Cron.unsafeParse(expression))

  /** @param cron
    *   [[CronExpr]] to base [[Schedule]] on.
    * @return
    *   [[Schedule]] from cron expression
    */
  def fromCronExpr(cron: CronExpr): Schedule =
    def computeNext(previous: LocalDateTime): (LocalDateTime, Option[FiniteDuration]) =
      val next = cron.next(previous)
      val duration = next.map(n => ChronoUnit.MILLIS.between(previous, n).millis)
      println("COMPUTED " + duration)
      (next.getOrElse(previous), duration)

    Schedule.computed(LocalDateTime.now(), computeNext)
  end fromCronExpr
end CronSchedule
