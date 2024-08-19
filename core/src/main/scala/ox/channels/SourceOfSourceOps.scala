package ox.channels

import ox.*
import ox.channels.*
import ox.channels.ChannelClosedUnion.isValue

extension [U](parentSource: Source[Source[U]]) {

  /** Pipes the elements of child sources into the output source. If the parent source or any of the child sources emit an error, the
    * pulling stops and the output source emits the error.
    */
  def flatten(using Ox, StageCapacity): Source[U] = {
    val c2 = StageCapacity.newChannel[U]

    forkPropagate(c2) {
      var pool = List[Source[Source[U]] | Source[U]](parentSource)
      repeatWhile {
        selectOrClosed(pool) match {
          case ChannelClosed.Done =>
            // TODO: best to remove the specific channel that signalled to be Done
            pool = pool.filterNot(_.isClosedForReceiveDetail.contains(ChannelClosed.Done))
            if pool.isEmpty then
              c2.doneOrClosed()
              false
            else true
          case ChannelClosed.Error(e) =>
            c2.errorOrClosed(e)
            false
          case t: Source[U] @unchecked =>
            pool = t :: pool
            true
          case r: U @unchecked =>
            c2.sendOrClosed(r).isValue
        }
      }
    }

    c2
  }
}
