package ox.resilience

/** Determines the algorithm to use for the rate limiter */
trait RateLimiterAlgorithm:

  /** Acquires a permit to execute the operation. This method should block until a permit is available. */
  final def acquire(): Unit =
    acquire(1)

  /** Acquires permits to execute the operation. This method should block until a permit is available. */
  def acquire(permits: Int): Unit

  /** Tries to acquire a permit to execute the operation. This method should not block. */
  final def tryAcquire(): Boolean =
    tryAcquire(1)

  /** Tries to acquire permits to execute the operation. This method should not block. */
  def tryAcquire(permits: Int): Boolean

  /** Updates the internal state of the rate limiter to check whether new operations can be accepted. */
  def update(): Unit

  /** Returns the time in nanoseconds that needs to elapse until the next update. It should not modify internal state. */
  def getNextUpdate: Long

  /** Runs the operation, allowing the algorithm to take into account its duration, if needed. */
  final def runOperation[T](operation: => T): T = runOperation(operation, 1)

  /** Runs the operation, allowing the algorithm to take into account its duration, if needed. */
  def runOperation[T](operation: => T, permits: Int): T

end RateLimiterAlgorithm
