package ox

import ox.channels.Actor
import ox.channels.ActorRef

import java.util.concurrent.StructuredTaskScope
import java.util.concurrent.atomic.AtomicReference
import scala.annotation.implicitNotFound
import ox.channels.BufferCapacity
import ox.internal.ThreadHerd

/** Capability granted by an [[unsupervised]] concurrency scope (as well as, via subtyping, by [[supervised]] and [[supervisedError]]).
  *
  * Represents a capability to:
  *   - fork unsupervised, asynchronously running computations in a concurrency scope. Such forks can be created using [[forkUnsupervised]]
  *     or [[forkCancellable]].
  *   - register resources to be cleaned up after the scope ends
  *
  * @see
  *   [[Ox]], [[OxError]]
  */
@implicitNotFound(
  "This operation must be run within a `supervised`, `supervisedError` or `unsupervised` block. Alternatively, you must require that the enclosing method is run within a scope, by adding a `using OxUnsupervised` parameter list."
)
trait OxUnsupervised:
  private[ox] def herd: ThreadHerd
  private[ox] def finalizers: AtomicReference[List[() => Unit]]
  private[ox] def supervisor: Supervisor[Nothing]
  private[ox] def addFinalizer(f: () => Unit): Unit = finalizers.updateAndGet(f :: _).discard
end OxUnsupervised

/** Capability granted by an [[supervised]] or [[supervisedError]] concurrency scope.
  *
  * Represents a capability to:
  *   - fork supervised or unsupervised, asynchronously running computations in a concurrency scope. Such forks can be created using
  *     [[fork]], [[forkUser]], [[forkUnsupervised]] or [[forkCancellable]].
  *   - register resources to be cleaned up after the scope ends
  *
  * @see
  *   [[OxError]], [[OxUnsupervised]]
  */
@implicitNotFound(
  "This operation must be run within a `supervised` or `supervisedError` block. Alternatively, you must require that the enclosing method is run within a scope, by adding a `using Ox` parameter list."
)
trait Ox extends OxUnsupervised:
  private[ox] def asNoErrorMode: OxError[Nothing, [T] =>> T]

  /** @see inScopeRunner */
  private[ox] lazy val runInScopeActor: ActorRef[RunInScope] = Actor.create(
    new RunInScope:
      def apply(f: Ox ?=> Unit): Unit = f(using Ox.this)
  )(using this, BufferCapacity(-1)) // since the actor is designed to interface with external libraries, we make sure sending never blocks
end Ox

private[ox] trait RunInScope:
  def apply(f: Ox ?=> Unit): Unit

/** Capability granted by a [[supervisedError]] concurrency scope.
  *
  * Represents a capability to:
  *   - fork supervised or unsupervised, asynchronously running computations in a concurrency scope. Such forks can be created e.g. using
  *     [[forkError]], [[forkUserError]], [[fork]], [[forkUnsupervised]], [[forkCancellable]].
  *   - register resources to be cleaned up after the scope ends
  *
  * `OxError` is similar to [[Ox]], however it additionally allows completing forks with application errors of type `E` in context `F`. Such
  * errors cause enclosing scope to end, and any forks that are still running to be cancelled
  */
@implicitNotFound(
  "This operation must be run within a `supervisedError` block. Alternatively, you must require that the enclosing method is run within a scope, by adding a `using OxError[E, F]` parameter list, using the desired error mode type parameters."
)
case class OxError[E, F[_]](
    private[ox] val herd: ThreadHerd,
    private[ox] val finalizers: AtomicReference[List[() => Unit]],
    private[ox] val supervisor: Supervisor[E],
    private[ox] val errorMode: ErrorMode[E, F]
) extends Ox:
  override private[ox] def asNoErrorMode: OxError[Nothing, [T] =>> T] =
    if errorMode == NoErrorMode then this.asInstanceOf[OxError[Nothing, [T] =>> T]]
    else OxError(herd, finalizers, supervisor, NoErrorMode)
end OxError

object OxError:
  def apply[E, F[_]](s: Supervisor[E], em: ErrorMode[E, F]): OxError[E, F] =
    OxError(ThreadHerd(oxThreadFactory), new AtomicReference(Nil), s, em)
