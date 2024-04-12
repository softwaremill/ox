package ox

import java.util.concurrent.StructuredTaskScope
import java.util.concurrent.atomic.AtomicReference
import scala.annotation.implicitNotFound

/** Represents a capability to:
  *   - fork unsupervised, asynchronously running computations in a concurrency scope. Such forks can be created e.g. using
  *     [[forkUnsupervised]].
  *   - register resources to be cleaned up after the scope ends
  *
  * This capability is provided by scopes created using [[supervised]], [[supervisedError]] or [[unsupervised]].
  *
  * @see
  *   [[Ox]], [[OxError]]
  */
@implicitNotFound(
  "This operation must be run within a `supervised`, `supervisedError` or `unsupervised` block. Alternatively, you must require that the enclosing method is run within a scope, by adding a `using Ox` parameter list."
)
trait OxUnsupervised:
  private[ox] def scope: StructuredTaskScope[Any]
  private[ox] def finalizers: AtomicReference[List[() => Unit]]
  private[ox] def supervisor: Supervisor[Nothing]
  private[ox] def addFinalizer(f: () => Unit): Unit = finalizers.updateAndGet(f :: _).discard

/** Represents a capability to:
  *   - fork supervised or unsupervised, asynchronously running computations in a concurrency scope. Such forks can be created e.g. using
  *     [[fork]].
  *   - register resources to be cleaned up after the scope ends
  *
  * This capability is provided by scopes created using [[supervised]] or [[supervisedError]].
  *
  * @see
  *   [[OxError]]
  */
@implicitNotFound(
  "This operation must be run within a `supervised` or `supervisedError` block. Alternatively, you must require that the enclosing method is run within a scope, by adding a `using Ox` parameter list."
)
trait Ox extends OxUnsupervised:
  private[ox] def asNoErrorMode: OxError[Nothing, [T] =>> T]

/** Represents a capability to:
  *   - fork supervised or unsupervised, asynchronously running computations in a concurrency scope. Such forks can be created e.g. using
  *     [[forkError]].
  *   - register resources to be cleaned up after the scope ends
  *
  * This capability is provided by scopes created using [[supervisedError]].
  *
  * `OxError` is similar to [[Ox]], however it additionally allows completing forks with application errors of type `E` in context `F`. Such
  * errors cause enclosing scope to end, and any forks that are still running to be cancelled
  */
@implicitNotFound(
  "This operation must be run within a `supervisedError` block. Alternatively, you must require that the enclosing method is run within a scope, by adding a `using OxError[E, F]` parameter list, using the desired error mode type parameters."
)
case class OxError[E, F[_]](
    private[ox] val scope: StructuredTaskScope[Any],
    private[ox] val finalizers: AtomicReference[List[() => Unit]],
    private[ox] val supervisor: Supervisor[E],
    private[ox] val errorMode: ErrorMode[E, F]
) extends Ox:
  override private[ox] def asNoErrorMode: OxError[Nothing, [T] =>> T] =
    if errorMode == NoErrorMode then this.asInstanceOf[OxError[Nothing, [T] =>> T]]
    else OxError(scope, finalizers, supervisor, NoErrorMode)

object OxError:
  def apply[E, F[_]](s: Supervisor[E], em: ErrorMode[E, F]): OxError[E, F] = OxError(DoNothingScope[Any](), new AtomicReference(Nil), s, em)
