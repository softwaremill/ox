package ox.internal

import ox.ForkLocalMap
import ox.OxUnsupervised

/** Should only ever be updated when starting a new scope, for the duration of the scope's lifetime. Used to verify that forks are properly
  * started, within a running concurrency scope, on a thread that is part of some scope in the tree.
  */
private[ox] val currentScope: ThreadLocal[OxUnsupervised] =
  ResourceRuntime.currentConcurrencyScope.asInstanceOf[ThreadLocal[OxUnsupervised]]

/** Runs `f` with the given concurrency scope bound to the current thread. */
private[ox] def withCurrentScope[T](scope: OxUnsupervised)(f: => T): T =
  val previousScope = currentScope.get()
  try
    currentScope.set(scope)
    f
  finally
    if previousScope == null then currentScope.remove()
    else currentScope.set(previousScope)
end withCurrentScope

private[ox] def currentLocals: ForkLocalMap =
  val scope = currentScope.get()
  if scope == null then ForkLocalMap(Map.empty) else scope.locals
