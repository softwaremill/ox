package ox.channels

import ox.*

import java.util.concurrent.CompletableFuture
import scala.util.control.NonFatal

object Actor:
  /** Creates a new actor, that is a fork in the current concurrency scope, which protects a mutable resource (`logic`) and executes
    * invocations on it serially, one after another. It is guaranteed that `logic` will be accessed by at most one thread at a time. The
    * methods of `logic: T` define the actor's interface (the messages that can be "sent to the actor").
    *
    * Invocations can be scheduled using the returned `ActorRef`. When an invocation is an [[ActorRef.ask]], any non-fatal exceptions are
    * propagated to the caller, and the actor continues. Fatal exceptions, or exceptions that occur during [[ActorRef.tell]] invocations,
    * cause the actor's channel to be closed with an error, and are propagated to the enclosing scope.
    *
    * The actor's mailbox (incoming channel) will have a capacity as specified by the [[BufferCapacity]] in scope.
    */
  def create[T](logic: T, close: Option[T => Unit] = None)(using ox: Ox, sc: BufferCapacity): ActorRef[T] =
    val c = BufferCapacity.newChannel[T => Unit]
    val ref = ActorRef(c)
    forkDiscard {
      try
        forever {
          val m = c.receive()
          try m(logic)
          catch
            case t: Throwable =>
              c.error(t)
              throw t
        }
      finally close.foreach(c => uninterruptible(c(logic)))
    }
    ref
  end create
end Actor

class ActorRef[T](c: Sink[T => Unit]):
  /** Send an invocation to the actor and await for the result.
    *
    * The `f` function should be an invocation of a method on `T` and should not directly or indirectly return the `T` value, as this might
    * expose the actor's internal mutable state to other threads.
    *
    * Any non-fatal exceptions thrown by `f` will be propagated to the caller and the actor will continue processing other invocations.
    * Fatal exceptions will be propagated to the actor's enclosing scope, and the actor will close.
    */
  def ask[U](f: T => U): U =
    val cf = new CompletableFuture[U]()
    c.send { t =>
      try cf.complete(f(t)).discard
      catch
        case NonFatal(e) =>
          // since this is an ask, only propagating the exception to the caller, not to the scope
          cf.completeExceptionally(e).discard
        case t: Throwable =>
          // fatal exceptions are propagated to the scope (e.g. InterruptedException)
          cf.completeExceptionally(t).discard
          throw t
    }
    unwrapExecutionException(cf.get())
  end ask

  /** Send an invocation to the actor that should be processed in the background (fire-and-forget). Might block until there's enough space
    * in the actor's mailbox (incoming channel).
    *
    * Any exceptions thrown by `f` will be propagated to the actor's enclosing scope, and the actor will close.
    */
  def tell(f: T => Unit): Unit = c.send(f)
end ActorRef
