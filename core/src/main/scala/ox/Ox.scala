package ox

import jdk.incubator.concurrent.{ScopedValue, StructuredTaskScope}

import java.io.Closeable
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.{ArrayBlockingQueue, Callable, CompletableFuture}
import scala.annotation.{implicitNotFound, tailrec}
import scala.concurrent.{ExecutionException, TimeoutException}
import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.util.{Failure, Success, Try}
import scala.util.control.NoStackTrace

@implicitNotFound(
  "This operation must be run within a `scoped` block. Alternatively, you must require that the enclosing method is run within a `scoped` block, by adding a `using Ox` parameter list."
)
case class Ox(
    scope: StructuredTaskScope[Any],
    finalizers: AtomicReference[List[() => Unit]]
):
  private[ox] def addFinalizer(f: () => Unit): Unit = finalizers.updateAndGet(f :: _)

// errors: .either, .orThrow
