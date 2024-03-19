package ox.retry

import ox.retry.RetryLifecycle.*

/** A case class representing the lifecycle of a retry operation. It contains two optional callbacks: `beforeEachAttempt` and
  * `afterEachAttempt`.
  *
  * @param beforeEachAttempt
  *   A function that is executed before each retry attempt. It takes the attempt number as a parameter. By default, it's an empty function.
  * @param afterEachAttempt
  *   A function that is executed after each retry attempt. It takes the attempt number and the result of the attempt as parameters. The
  *   result is represented as an `Either` type, where `Left` represents an error and `Right` represents a successful result. By default,
  *   it's an empty function.
  * @tparam E
  *   The type of the error in case the retry operation fails.
  * @tparam T
  *   The type of the successful result in case the retry operation succeeds.
  */
case class RetryLifecycle[E, T](
    beforeEachAttempt: BeforeEachAttempt = _ => (),
    afterEachAttempt: AfterEachAttempt[E, T] = (_, _: Either[E, T]) => ()
)

object RetryLifecycle:
  type BeforeEachAttempt = Int => Unit
  type AfterEachAttempt[E, T] = (Int, Either[E, T]) => Unit

  /** Creates a `RetryLifecycle` instance with default empty callbacks.
    *
    * @tparam E
    *   The type of the error in case the retry operation fails.
    * @tparam T
    *   The type of the successful result in case the retry operation succeeds.
    * @return
    *   A `RetryLifecycle` instance with default empty callbacks.
    */
  def default[E, T]: RetryLifecycle[E, T] = RetryLifecycle()

  /** Creates a `RetryLifecycle` instance with a specific `beforeEachAttempt` callback.
    *
    * @param f
    *   The function to be used as `beforeEachAttempt`.
    * @tparam E
    *   The type of the error in case the retry operation fails.
    * @tparam T
    *   The type of the successful result in case the retry operation succeeds.
    * @return
    *   A `RetryLifecycle` instance with the specified `beforeEachAttempt`.
    */
  def beforeEachAttempt[E, T](f: BeforeEachAttempt): RetryLifecycle[E, T] = RetryLifecycle(beforeEachAttempt = f)

  /** Creates a `RetryLifecycle` instance with a specific `afterEachAttempt` callback.
    *
    * @param f
    *   The function to be used as `afterEachAttempt`.
    * @tparam E
    *   The type of the error in case the retry operation fails.
    * @tparam T
    *   The type of the successful result in case the retry operation succeeds.
    * @return
    *   A `RetryLifecycle` instance with the specified `afterEachAttempt`.
    */
  def afterEachAttempt[E, T](f: AfterEachAttempt[E, T]): RetryLifecycle[E, T] = RetryLifecycle(afterEachAttempt = f)
