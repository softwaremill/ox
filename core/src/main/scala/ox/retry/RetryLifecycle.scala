package ox.retry

import ox.retry.RetryLifecycle.*

/** A case class representing the lifecycle of a retry operation. It contains two optional actions: `preAction` and `postAction`.
  *
  * @param preAction
  *   A function that is executed before each retry attempt. It takes the attempt number as a parameter. By default, it's an empty function.
  * @param postAction
  *   A function that is executed after each retry attempt. It takes the attempt number and the result of the attempt as parameters. The
  *   result is represented as an `Either` type, where `Left` represents an error and `Right` represents a successful result. By default,
  *   it's an empty function.
  * @tparam E
  *   The type of the error in case the retry operation fails.
  * @tparam T
  *   The type of the successful result in case the retry operation succeeds.
  */
case class RetryLifecycle[E, T](
    preAction: RetryLifecycle.PreAction = _ => (),
    postAction: RetryLifecycle.PostAction[E, T] = (_, _: Either[E, T]) => ()
)

object RetryLifecycle:
  type PreAction = Int => Unit
  type PostAction[E, T] = (Int, Either[E, T]) => Unit

  /** Creates a `RetryLifecycle` instance with a specific `preAction`.
    *
    * @param f
    *   The function to be used as `preAction`.
    * @tparam E
    *   The type of the error in case the retry operation fails.
    * @tparam T
    *   The type of the successful result in case the retry operation succeeds.
    * @return
    *   A `RetryLifecycle` instance with the specified `preAction`.
    */
  def preAction[E, T](f: PreAction): RetryLifecycle[E, T] = RetryLifecycle(preAction = f)

  /** Creates a `RetryLifecycle` instance with a specific `postAction`.
    *
    * @param f
    *   The function to be used as `postAction`.
    * @tparam E
    *   The type of the error in case the retry operation fails.
    * @tparam T
    *   The type of the successful result in case the retry operation succeeds.
    * @return
    *   A `RetryLifecycle` instance with the specified `postAction`.
    */
  def postAction[E, T](f: PostAction[E, T]): RetryLifecycle[E, T] = RetryLifecycle(postAction = f)
