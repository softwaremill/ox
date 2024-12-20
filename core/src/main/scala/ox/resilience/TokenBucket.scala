package ox.resilience

import java.util.concurrent.Semaphore

case class TokenBucket(bucketSize: Int, initSize: Option[Int] = None):
  private val semaphore = Semaphore(initSize.getOrElse(bucketSize))

  def tryAcquire(permits: Int): Boolean =
    semaphore.tryAcquire(permits)

  def acquire(permits: Int): Unit =
    semaphore.acquire(permits)

  def release(permits: Int): Unit =
    val availablePermits = semaphore.availablePermits()
    val toRelease = if availablePermits + permits >= bucketSize then bucketSize - availablePermits else permits
    semaphore.release(toRelease)

end TokenBucket
