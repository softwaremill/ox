package ox

import java.util.function.Supplier

class ForkLocal[T](private[ox] val default: T):
  def get(): T = currentForkLocalMap.get().get(this)

  // No `unsupervisedWhere`: the unsupervised scope doesn't start a new thread, hence we cannot use the thread-local-based propagation
  // mechanism, as it would change the value in a too wide scope. When the implementation is once again ScopedValue-based, this method
  // can be re-introduced.

  /** Creates a new [[supervised]] scope, where the value of this fork local is set to `newValue`, only for the duration of the scope.
    *
    * **Warning:** due to the "structured" nature of setting a fork local's value, forks created using external (wider) scopes will not see
    * the updated value, but the one associated with the original scope.
    */
  def supervisedWhere[U](newValue: T)(f: Ox ?=> U): U =
    supervised(forkLocalWhere(this, newValue)(f))

  /** Creates a new [[supervisedError]] scope, where the value of this fork local is set to `newValue`, only for the duration of the scope.
    *
    * **Warning:** due to the "structured" nature of setting a fork local's value, forks created using external (wider) scopes will not see
    * the updated value, but the one associated with the original scope.
    */
  def supervisedErrorWhere[E, F[_], U](errorMode: ErrorMode[E, F])(newValue: T)(f: OxError[E, F] ?=> F[U]): F[U] =
    supervisedError(errorMode)(forkLocalWhere(this, newValue)(f))
end ForkLocal

object ForkLocal:
  def apply[T](initialValue: T): ForkLocal[T] = new ForkLocal(initialValue)

private def forkLocalWhere[T, U](fl: ForkLocal[T], t: T)(f: => U): U =
  currentForkLocalMap.set(currentForkLocalMap.get().set(fl, t))
  f

/** Until the structured concurrency & scoped values JEPs stabilize in an LTS release, replacement for `ScopedValue`s. Storing the local
  * values in a thread-local map, but restricting the API to access them to allow only structured usage. The API also reflects the
  * restrictions imposed by the scoped values JEP, that is: the bindings cannot change within a scope; hence, a new scope is always started
  * as part of setting a value, and the new value is only visible within that scope.
  */
private[ox] class ForkLocalMap(storage: Map[ForkLocal[_], Any]):
  def get[T](key: ForkLocal[T]): T = storage.getOrElse(key, key.default).asInstanceOf[T]
  def set[T](key: ForkLocal[T], value: T): ForkLocalMap = new ForkLocalMap(storage.updated(key, value))

/** This thread-local should only be updated after creating a fork, to propagate the local values, and when setting a new value, as the
  * first operation after a fork is created. It should never be updated in any other place.
  */
private[ox] val currentForkLocalMap: ThreadLocal[ForkLocalMap] = ThreadLocal.withInitial(
  new Supplier[ForkLocalMap]:
    def get(): ForkLocalMap = ForkLocalMap(Map.empty)
)
