package ox

import ox.internal.currentLocals

class ForkLocal[T](default: T):
  def get(): T = currentLocals.get(this).getOrElse(default) // reading locals using the most-nested concurrency scope

  /** Creates a new [[unsupervised]] scope, where the value of this fork local is set to `newValue`, only for the duration of the scope.
    *
    * **Warning:** due to the "structured" nature of setting a fork local's value, forks created using external (wider) scopes will not see
    * the updated value, but the one associated with the original scope.
    *
    * **Warning:** It is advisable to use [[supervised]] scopes if possible, as they minimize the chances of an error to go unnoticed.
    * [[unsupervised]] scopes are considered an advanced feature, and should be used with caution.
    */
  def unsupervisedWhere[U](newValue: T)(f: OxUnsupervised ?=> U): U =
    // the scoped values need to be set inside the thread that's used to create the new scope, but
    // before starting the scope itself, as scoped value bindings can't change after the scope is started
    unsupervised(currentLocals.set(this, newValue), f)

  /** Creates a new [[supervised]] scope, where the value of this fork local is set to `newValue`, only for the duration of the scope.
    *
    * **Warning:** due to the "structured" nature of setting a fork local's value, forks created using external (wider) scopes will not see
    * the updated value, but the one associated with the original scope.
    */
  def supervisedWhere[U](newValue: T)(f: Ox ?=> U): U =
    supervisedError(NoErrorMode, currentLocals.set(this, newValue))(f)

  /** Creates a new [[supervisedError]] scope, where the value of this fork local is set to `newValue`, only for the duration of the scope.
    *
    * **Warning:** due to the "structured" nature of setting a fork local's value, forks created using external (wider) scopes will not see
    * the updated value, but the one associated with the original scope.
    */
  def supervisedErrorWhere[E, F[_], U](errorMode: ErrorMode[E, F])(newValue: T)(f: OxError[E, F] ?=> F[U]): F[U] =
    supervisedError(errorMode, currentLocals.set(this, newValue))(f)
end ForkLocal

object ForkLocal:
  def apply[T](initialValue: T): ForkLocal[T] = new ForkLocal(initialValue)

/** Until the structured concurrency & scoped values JEPs stabilize in an LTS release, replacement for `ScopedValue`s. The local values are
  * stored in a map bound to the concurrency scope, and the binding can only be updated by starting a new concurrency scope (same as with
  * the `ScopedValue`-based implementation). That way locals never escape their scope.
  */
private[ox] class ForkLocalMap(storage: Map[ForkLocal[_], Any]):
  def get[T](key: ForkLocal[T]): Option[T] = storage.get(key).asInstanceOf[Option[T]]
  def set[T](key: ForkLocal[T], value: T): ForkLocalMap = new ForkLocalMap(storage.updated(key, value))
