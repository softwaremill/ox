package ox

import scala.reflect.ClassTag

/** Describes the representation of application errors. Such errors have type `E` and are reported in context `F`.
  *
  * An application error is a value which is returned as a successful completion of a fork. However, unlike regular values, such error
  * values are reported and cause the enclosing scope to end.
  *
  * For any type `T`, `F[T]` represents a value which might either:
  *
  *   - be successful; in that case, a `T` instance can be derived
  *   - be an error; in that case, an `E` instance can be derived
  */
trait ErrorMode[E, F[_]] {

  /** Check if `f` represents an error, or success. */
  def isError[T](f: F[T]): Boolean

  /** Get the `E` error value, called only if `isError(f)` returns `true`. */
  def getError[T](f: F[T]): E

  /** Get the `T` successful value, called only if `isError(f)` returns `false`. */
  def getT[T](f: F[T]): T

  /** Embed a value of type `T` into the `F` context. */
  def pure[T](t: T): F[T]

  /** Embed an error into the `F` context. */
  def pureError[T](e: E): F[T]

  /** Adds a suppressed exception to the value being represented by `error`. This is only called if `isError(error)` returns `true`. By
    * default, the suppressed exception is discarded and the original value is returned.
    */
  def addSuppressed[T](error: F[T], e: Throwable): F[T] = error
}

/** An error mode which doesn't allow reporting application errors.
  *
  * However, forks can still fail by throwing exceptions.
  */
object NoErrorMode extends ErrorMode[Nothing, [T] =>> T] {
  override def isError[T](f: T): Boolean = false
  override def getError[T](f: T): Nothing = throw new IllegalStateException()
  override def getT[T](f: T): T = f
  override def pure[T](t: T): T = t
  override def pureError[T](e: Nothing): T = e
}

/** An error mode where errors are represented as `Either[E, T]` instances. */
class EitherMode[E] extends ErrorMode[E, [T] =>> Either[E, T]] {
  override def isError[T](f: Either[E, T]): Boolean = f.isLeft
  override def getError[T](f: Either[E, T]): E = f.left.get
  override def getT[T](f: Either[E, T]): T = f.right.get
  override def pure[T](t: T): Either[E, T] = Right(t)
  override def pureError[T](e: E): Either[E, T] = Left(e)
}

/** An error mode where errors are represented as an union of `E` and `T` values. `T` should always be different from `E`, otherwise errors
  * can't be told apart from successful values. To discern between the two, a class tag for `E` is necessary (it must have a distinct
  * run-time representation).
  */
class UnionMode[E: ClassTag] extends ErrorMode[E, [T] =>> E | T] {
  override def isError[T](f: E | T): Boolean = summon[ClassTag[E]].runtimeClass.isInstance(f)
  override def getError[T](f: E | T): E = f.asInstanceOf[E]
  override def getT[T](f: E | T): T = f.asInstanceOf[T]
  override def pure[T](t: T): E | T = t
  override def pureError[T](e: E): E | T = e
}
