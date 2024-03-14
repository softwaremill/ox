package ox

import java.util.concurrent.Callable

private def scopedValueWhere[T, U](sv: ScopedValue[T], t: T)(f: => U): U =
  ScopedValue.callWhere(sv, t, (() => f): Callable[U])

class ForkLocal[T](scopedValue: ScopedValue[T], default: T):
  def get(): T = scopedValue.orElse(default)

  /** Creates a new [[scoped]] scope, where the value of this fork local is set to `newValue`, only for the duration of the scope.
    *
    * **Warning:** due to the "structured" nature of setting a fork local's value, forks using external (wider) scopes should not be
    * created, as an attempt to do so will throw a [[java.util.concurrent.StructureViolationException]].
    *
    * **Warning:** It is advisable to use [[supervised]] scopes if possible, as they minimise the chances of an error to go unnoticed.
    * [[scoped]] scopes are considered an advanced feature, and should be used with caution.
    */
  def scopedWhere[U](newValue: T)(f: Ox ?=> U): U =
    // the scoped values need to be set inside the thread that's used to create the new scope, but
    // before starting the scope itself, as scoped value bindings can't change after the scope is started
    scopedValueWhere(scopedValue, newValue)(scoped(f))

  /** Creates a new [[supervised]] scope, where the value of this fork local is set to `newValue`, only for the duration of the scope.
    *
    * **Warning:** due to the "structured" nature of setting a fork local's value, forks using external (wider) scopes should not be
    * created, as an attempt to do so will throw a [[java.util.concurrent.StructureViolationException]].
    */
  def supervisedWhere[U](newValue: T)(f: Ox ?=> U): U =
    scopedValueWhere(scopedValue, newValue)(supervised(f))

  /** Creates a new [[supervisedError]] scope, where the value of this fork local is set to `newValue`, only for the duration of the scope.
    *
    * **Warning:** due to the "structured" nature of setting a fork local's value, forks using external (wider) scopes should not be
    * created, as an attempt to do so will throw a [[java.util.concurrent.StructureViolationException]].
    */
  def supervisedErrorWhere[E, F[_], U](errorMode: ErrorMode[E, F])(newValue: T)(f: OxError[E, F] ?=> F[U]): F[U] =
    scopedValueWhere(scopedValue, newValue)(supervisedError(errorMode)(f))

object ForkLocal:
  def apply[T](initialValue: T): ForkLocal[T] = new ForkLocal(ScopedValue.newInstance[T](), initialValue)
