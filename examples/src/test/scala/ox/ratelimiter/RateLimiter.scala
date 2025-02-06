package ox.ratelimiter

import org.slf4j.LoggerFactory
import ox.ratelimiter.RateLimiterQueue.{Run, RunAfter}
import ox.{discard, sleep, Ox, fork, forkDiscard, supervised}

import java.util.concurrent.{ArrayBlockingQueue, BlockingQueue, CompletableFuture, Future}
import scala.annotation.tailrec
import scala.concurrent.duration.*

class RateLimiter(queue: BlockingQueue[RateLimiterMsg]):
  def runLimited[T](f: => T): Future[T] =
    val cf = new CompletableFuture[T]()
    queue.put(Schedule { () =>
      try cf.complete(f).discard
      catch case e: Throwable => cf.completeExceptionally(e).discard
    })
    cf
end RateLimiter

object RateLimiter:
  private val logger = LoggerFactory.getLogger(this.getClass)

  def withRateLimiter[T](maxRuns: Int, per: FiniteDuration)(f: RateLimiter => T): T = supervised {
    val queue = new ArrayBlockingQueue[RateLimiterMsg](32)
    forkDiscard {
      try runQueue(RateLimiterQueue(maxRuns, per.toMillis), queue)
      finally logger.info("Stopping rate limiter")
    }
    f(new RateLimiter(queue))
  }

  @tailrec
  private def runQueue(data: RateLimiterQueue[() => Unit], queue: BlockingQueue[RateLimiterMsg])(using Ox): Unit =
    // (1) take a message from the queue (or wait until one is available)
    val msg = queue.take()

    // (2) modify the data structure accordingly
    val data2 = msg match
      case ScheduledRunQueue => data.notScheduled
      case Schedule(t)       => data.enqueue(t)

    // (3) run the rate limiter queue: obtain the rate-limiter-tasks to be run
    val (tasks, data3) = data2.run(System.currentTimeMillis())

    // (4) run each rate-limiter-task in the background
    tasks.foreach {
      case Run(run) => fork(run())
      case RunAfter(millis) =>
        fork {
          sleep(millis.millis)
          queue.put(ScheduledRunQueue)
        }
    }

    // (7) recursive call to handle the next message, using the updated data structure
    runQueue(data3, queue)
  end runQueue
end RateLimiter

private sealed trait RateLimiterMsg
private case object ScheduledRunQueue extends RateLimiterMsg
private case class Schedule(t: () => Unit) extends RateLimiterMsg

private class StopException extends RuntimeException
