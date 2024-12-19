package ox.resilience

import java.util.concurrent.Semaphore

case class TokenBucket(bucketSize: Int):
  private val semaphore = Semaphore(bucketSize)

  def tryAcquire(permits: Int): Boolean =
    semaphore.tryAcquire(permits)

  def release(permits: Int): Unit =
    val availablePermits = semaphore.availablePermits()
    val toRelease = if availablePermits + permits >= bucketSize then bucketSize - availablePermits else permits
    semaphore.release(toRelease)

end TokenBucket
