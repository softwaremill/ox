package ox.internal

import ox.OxUnsupervised
import ox.ForkLocalMap

/** Should only ever be updated when starting a new scope, for the duration of the scope's lifetime. Used to verify that forks are properly
  * started, within a running concurrency scope, on a thread that is part of some scope in the tree.
  */
private[ox] val currentScope: ThreadLocal[OxUnsupervised] = new ThreadLocal[OxUnsupervised]()

private[ox] def currentLocals: ForkLocalMap =
  val scope = currentScope.get()
  if scope == null then ForkLocalMap(Map.empty) else scope.locals
