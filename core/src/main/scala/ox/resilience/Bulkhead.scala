package ox.resilience

import java.util.concurrent.Semaphore
import scala.concurrent.duration.*

case class Bulkhead(maxConcurrentCalls: Int):
  private val semaphore = Semaphore(maxConcurrentCalls)

  def runOrDrop[T](operation: => T): Option[T] =
    if semaphore.tryAcquire() then
      try Some(operation)
      finally semaphore.release()
    else None

  def runOrDropWithTimeout[T](timeoutDuration: FiniteDuration)(operation: => T): Option[T] =
    Option.when(semaphore.tryAcquire(timeoutDuration.toMillis, MILLISECONDS))(operation)
end Bulkhead
