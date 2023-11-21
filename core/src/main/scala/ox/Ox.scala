package ox

import java.util.concurrent.StructuredTaskScope
import java.util.concurrent.atomic.AtomicReference
import scala.annotation.implicitNotFound

@implicitNotFound(
  "This operation must be run within a `supervised` or `scoped` block. Alternatively, you must require that the enclosing method is run within a scope, by adding a `using Ox` parameter list."
)
case class Ox(
    scope: StructuredTaskScope[Any],
    finalizers: AtomicReference[List[() => Unit]],
    supervisor: Supervisor
):
  private[ox] def addFinalizer(f: () => Unit): Unit = finalizers.updateAndGet(f :: _)
