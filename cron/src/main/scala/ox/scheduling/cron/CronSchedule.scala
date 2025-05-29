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
    def computeNext: FiniteDuration =
      val now = LocalDateTime.now()
      val next = cron.next(now)
      val duration = next.map(n => ChronoUnit.MILLIS.between(now, n))
      println("COMPUTED " + duration)
      duration.map(_.millis).getOrElse(Duration.Zero)

    Schedule.computed((), _ => ((), computeNext))
  end fromCronExpr
end CronSchedule
