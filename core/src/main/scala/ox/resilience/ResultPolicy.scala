package ox.resilience

/** A policy that allows to customize when a non-erroneous result is considered successful and when an error is worth retrying (which allows
  * for failing fast on certain errors).
  *
  * @param isSuccess
  *   A function that determines whether a non-erroneous result is considered successful. By default, every non-erroneous result is
  *   considered successful.
  * @param isWorthRetrying
  *   A function that determines whether an error is worth retrying. By default, all errors are retried.
  * @tparam E
  *   The error type of the operation. For operations returning a `T` or a `Try[T]`, this is fixed to `Throwable`. For operations returning
  *   an `Either[E, T]`, this can be any `E`.
  * @tparam T
  *   The successful result type for the operation.
  */
case class ResultPolicy[E, T](isSuccess: T => Boolean = (_: T) => true, isWorthRetrying: E => Boolean = (_: E) => true)

object ResultPolicy:
  /** A policy that considers every non-erroneous result successful and retries on any error. */
  def default[E, T]: ResultPolicy[E, T] = ResultPolicy()

  /** A policy that customizes when a non-erroneous result is considered successful, and retries all errors
    *
    * @param isSuccess
    *   A predicate that indicates whether a non-erroneous result is considered successful.
    */
  def successfulWhen[E, T](isSuccess: T => Boolean): ResultPolicy[E, T] = ResultPolicy(isSuccess = isSuccess)

  /** A policy that customizes which errors are retried, and considers every non-erroneous result successful
    * @param isWorthRetrying
    *   A predicate that indicates whether an erroneous result should be retried.
    */
  def retryWhen[E, T](isWorthRetrying: E => Boolean): ResultPolicy[E, T] = ResultPolicy(isWorthRetrying = isWorthRetrying)

  /** A policy that considers every non-erroneous result successful and never retries any error, i.e. fails fast */
  def neverRetry[E, T]: ResultPolicy[E, T] = ResultPolicy(isWorthRetrying = _ => false)
end ResultPolicy
