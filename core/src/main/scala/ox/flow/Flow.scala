package ox.flow

import ox.channels.ChannelClosed
import ox.channels.Source
import ox.repeatWhile
import scala.annotation.nowarn

/** Describes an asynchronous transformation pipeline. When run, emits elements of type `T`.
  *
  * A flow is lazy - evaluation happens only when it's run.
  *
  * Flows can be created using the [[Flow.usingSink]], [[Flow.fromValues]] and other `Flow.from*` methods, [[Flow.tick]] etc.
  *
  * Transformation stages can be added using the available combinators, such as [[Flow.map]], [[Flow.async]], [[Flow.grouped]], etc. Each
  * such method returns a new immutable `Flow` instance.
  *
  * Running a flow is possible using one of the `run*` methods, such as [[Flow.runToList]], [[Flow.runToChannel]] or [[Flow.runFold]].
  */
class Flow[+T](protected val last: FlowStage[T]) extends FlowOps[T] with FlowRunOps[T] with FlowIOOps[T] with FlowTextOps[T]

object Flow extends FlowCompanionOps with FlowCompanionIOOps

//

/** Contains the logic for running a single flow stage. As part of `run`s implementation, previous flow stages might be run, either
  * synchronously or asynchronously.
  */
trait FlowStage[+T]:
  def run(emit: FlowEmit[T]): Unit

object FlowStage:
  // The from-source stage is reified as a named class because of the optimiziation in .toChannel, which avoids
  // creating a pass-through fork, copying elements from one channel to another
  case class FromSource[T](source: Source[T]) extends FlowStage[T]:
    override def run(emit: FlowEmit[T]): Unit = FlowEmit.channelToEmit(source, emit)

//

trait FlowEmit[-T]:
  /** Emit a value to be processed downstream. */
  def apply(t: T): Unit

object FlowEmit:
  // suppressing the "New anonymous class definition will be duplicated at each inline site" warning: the whole point of this inline
  // is to create new FlowEmit instances
  @nowarn private[flow] inline def fromInline[T](inline f: T => Unit): FlowEmit[T] =
    new FlowEmit[T]:
      def apply(t: T): Unit = f(t)

  /** Propagates all elements to the given emit. */
  def channelToEmit[T](source: Source[T], emit: FlowEmit[T]): Unit =
    repeatWhile:
      val t = source.receiveOrClosed()
      t match
        case ChannelClosed.Done     => false
        case ChannelClosed.Error(r) => throw r
        case t: T @unchecked        => emit(t); true
end FlowEmit
