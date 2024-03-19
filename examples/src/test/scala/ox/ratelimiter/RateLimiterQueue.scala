package ox.ratelimiter

import ox.ratelimiter.RateLimiterQueue.*

import scala.collection.immutable.Queue

/** Queue of rate-limited computations. The computations will be *started* so that at any time, there's at most `maxRuns` in any time
  * `perMillis` window.
  *
  * Note that this does not take into account the duration of the computations, when they end or when they reach a remote server.
  *
  * @param scheduled
  *   Is an invocation of `run` already scheduled (by returning an appropriate task in the previous invocation): used to prevent scheduling
  *   too much runs; it's enough if there's only one run scheduled at any given time.
  * @tparam F
  *   Type of computations. Should be a lazy wrapper, so that computations can be enqueued for later execution.
  */
case class RateLimiterQueue[F](maxRuns: Int, perMillis: Long, lastTimestamps: Queue[Long], waiting: Queue[F], scheduled: Boolean):

  /** Given the timestamp, obtain a list of task which might include running a computation or scheduling a `run` invocation in the future,
    * and an updated queue.
    */
  def run(now: Long): (List[RateLimiterTask[F]], RateLimiterQueue[F]) = pruneTimestamps(now).doRun(now)

  /** Add a request to the queue. Doesn't run any pending requests. */
  def enqueue(f: F): RateLimiterQueue[F] = copy(waiting = waiting.enqueue(f))

  /** Before invoking a scheduled `run`, clear the scheduled flag. If needed, the next `run` invocation might include a `RunAfter` task. */
  def notScheduled: RateLimiterQueue[F] = copy(scheduled = false)

  private def doRun(now: Long): (List[RateLimiterTask[F]], RateLimiterQueue[F]) =
    if lastTimestamps.size < maxRuns then {
      waiting.dequeueOption match {
        case Some((io, w)) =>
          val (tasks, next) = copy(lastTimestamps = lastTimestamps.enqueue(now), waiting = w).run(now)(Run(io) :: tasks, next)
        case None =>
          (Nil, this)
      }
    } else if !scheduled then {
      val nextAvailableSlot = perMillis - (now - lastTimestamps.head)
      (List(RunAfter(nextAvailableSlot)), this.copy(scheduled = true))
    } else (Nil, this)

  /** Remove timestamps which are outside of the current time window, that is timestamps which are further from `now` than `timeMillis`. */
  private def pruneTimestamps(now: Long): RateLimiterQueue[F] =
    val threshold = now - perMillis
    copy(lastTimestamps = lastTimestamps.filter(_ >= threshold))

object RateLimiterQueue:
  def apply[F](maxRuns: Int, perMillis: Long): RateLimiterQueue[F] =
    RateLimiterQueue[F](maxRuns, perMillis, Queue.empty, Queue.empty, scheduled = false)

  sealed trait RateLimiterTask[F]
  case class Run[F](run: F) extends RateLimiterTask[F]
  case class RunAfter[F](millis: Long) extends RateLimiterTask[F]
