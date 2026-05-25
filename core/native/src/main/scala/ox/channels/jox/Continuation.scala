package ox.channels.jox

import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport

final class Continuation(val payload: AnyRef):
  private val creatingThread: Thread = Thread.currentThread()
  private val data: AtomicReference[AnyRef] = new AtomicReference(null)

  /** `true` if this continuation is for a sender; `false` for a receiver. */
  def isSender: Boolean = payload != null

  /** Resume the continuation with the given value. Returns `true` if successful. */
  def tryResume(value: AnyRef): Boolean =
    val result = data.compareAndSet(null, value)
    LockSupport.unpark(creatingThread)
    result

  /** Await for the continuation to be resumed. May throw InterruptedException. */
  @throws[InterruptedException]
  def await(segment: Segment, cellIndex: Int, isRendezvous: Boolean): AnyRef =
    var spinIterations = if isRendezvous then Continuation.RENDEZVOUS_SPINS else 0
    while data.get() == null do
      if spinIterations > 0 then
        Thread.onSpinWait()
        spinIterations -= 1
      else
        LockSupport.park()
        if Thread.interrupted() then
          if data.compareAndSet(null, ContinuationMarker.INTERRUPTED) then
            val _isSender = isSender
            segment.setCell(cellIndex, if _isSender then CellState.INTERRUPTED_SEND else CellState.INTERRUPTED_RECEIVE)
            if _isSender then segment.cellInterruptedSender()
            else segment.cellInterruptedReceiver()
            throw new InterruptedException()
          else Thread.currentThread().interrupt()
        end if
    end while
    data.get()
  end await
end Continuation

object Continuation:
  val RENDEZVOUS_SPINS: Int =
    val nproc = Runtime.getRuntime.availableProcessors()
    if nproc == 1 then 0 else if nproc <= 4 then 1 << 7 else 1 << 10
