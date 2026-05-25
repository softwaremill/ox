package ox.channels.jox

// Ported from: https://github.com/softwaremill/jox/blob/v1.1.2-channels/channels/src/main/java/com/softwaremill/jox/Channel.java

import java.util.concurrent.atomic.{AtomicLong, AtomicReference}
import java.util.concurrent.locks.LockSupport

final class Channel[T] private (val capacity: Int) extends Source[T] with Sink[T]:
  import CellState.*
  import Channel.{getSendersCounter, isClosed, setClosedFlag, TRY_SEND_NOT_SENT}
  import Segment.{SEGMENT_SIZE, NULL_SEGMENT, findAndMoveForward}

  val isRendezvous: Boolean = capacity == 0
  private inline def isUnlimited: Boolean = capacity < 0

  private val sendersAndClosedFlag = new AtomicLong(0L)
  private val receivers = new AtomicLong(0L)
  private val bufferEnd = new AtomicLong(capacity.toLong)

  private val sendSegment: AtomicReference[Segment] = new AtomicReference(null)
  private val receiveSegment: AtomicReference[Segment] = new AtomicReference(null)
  private val bufferEndSegment: AtomicReference[Segment] = new AtomicReference(null)
  private val closedReason: AtomicReference[ChannelClosed | Null] = new AtomicReference(null)

  locally:
    val isRendezvousOrUnlimited = isRendezvous || isUnlimited
    val firstSegment = new Segment(0, null, if isRendezvousOrUnlimited then 2 else 3, isRendezvousOrUnlimited)
    sendSegment.set(firstSegment)
    receiveSegment.set(firstSegment)
    bufferEndSegment.set(if isRendezvousOrUnlimited then NULL_SEGMENT else firstSegment)
    processInitialBuffer()

  private def processInitialBuffer(): Unit =
    var currentSegment = bufferEndSegment.get()
    val segmentsToProcess =
      if capacity <= 0 then 0
      else ((capacity + SEGMENT_SIZE - 1L) / SEGMENT_SIZE).toInt

    var segmentId = 0
    while segmentId < segmentsToProcess do
      currentSegment = findAndMoveForward(bufferEndSegment, currentSegment, segmentId.toLong).nn
      val cellsToProcess =
        val rem = if segmentId == segmentsToProcess - 1 then (capacity % SEGMENT_SIZE) else SEGMENT_SIZE
        if rem == 0 then SEGMENT_SIZE else rem
      currentSegment.setup_markCellsProcessed(cellsToProcess)
      segmentId += 1
  end processInitialBuffer

  // *******
  // Sending
  // *******

  @throws[InterruptedException]
  override def send(value: T): Unit =
    val r = sendOrClosed(value)
    r match
      case c: ChannelClosed => throw c.toException()
      case _                =>

  @throws[InterruptedException]
  override def sendOrClosed(value: T): AnyRef =
    doSend(value, null, null)

  /** Returns null when sent, ChannelClosed when closed, or StoredSelectClause if select is provided. */
  private def doSend(value: T, select: SelectInstance | Null, selectClause: SelectClause[?] | Null): AnyRef =
    if value == null then throw new NullPointerException()
    while true do
      val segment = sendSegment.get()
      val scf = sendersAndClosedFlag.getAndAdd(1L)
      val s = getSendersCounter(scf)

      val id = s / SEGMENT_SIZE
      val i = (s % SEGMENT_SIZE).toInt

      var seg = segment
      if segment.getId != id then
        seg = findAndMoveForward(sendSegment, segment, id)
        if seg == null then return closedReason.get().nn

        if seg.getId != id then
          sendersAndClosedFlag.compareAndSet(s, seg.getId * SEGMENT_SIZE)
          // continue - skipping interrupted cells
        else if isClosed(scf) then return closedReason.get().nn
        else
          val sendResult = updateCellSend(seg, i, s, value, select, selectClause, true)
          sendResult match
            case SendResult.BUFFERED | SendResult.AWAITED => return null
            case SendResult.RESUMED                       =>
              seg.cleanPrev()
              return null
            case ss: StoredSelectClause => return ss
            case SendResult.FAILED      =>
              seg.cleanPrev()
              // continue - trying with a new cell
            case SendResult.CLOSED => return closedReason.get().nn
            case _                 => throw new IllegalStateException(s"Unexpected result: $sendResult in channel: $this")
          end match
        end if
      else if isClosed(scf) then return closedReason.get().nn
      else
        val sendResult = updateCellSend(seg, i, s, value, select, selectClause, true)
        sendResult match
          case SendResult.BUFFERED | SendResult.AWAITED => return null
          case SendResult.RESUMED                       =>
            seg.cleanPrev()
            return null
          case ss: StoredSelectClause => return ss
          case SendResult.FAILED      =>
            seg.cleanPrev()
            // continue - trying with a new cell
          case SendResult.CLOSED => return closedReason.get().nn
          case _                 => throw new IllegalStateException(s"Unexpected result: $sendResult in channel: $this")
        end match
      end if
    end while
    throw new AssertionError("unreachable")
  end doSend

  // Non-blocking send
  override def trySendOrClosed(value: T): AnyRef =
    if value == null then throw new NullPointerException()
    while true do
      val segment = sendSegment.get()
      val scf = sendersAndClosedFlag.get()
      val s = getSendersCounter(scf)

      if isClosed(scf) then return closedReason.get().nn

      // capacity pre-check
      if capacity >= 0 then
        val bufEnd = bufferEnd.get()
        val r = receivers.get()
        if capacity == 0 then
          if s >= r then return Channel.TRY_SEND_NOT_SENT
        else if s >= bufEnd && s >= r then return Channel.TRY_SEND_NOT_SENT

      if !sendersAndClosedFlag.compareAndSet(scf, scf + 1) then () // continue
      else
        val id = s / SEGMENT_SIZE
        val i = (s % SEGMENT_SIZE).toInt

        var seg = segment
        if segment.getId != id then
          seg = findAndMoveForward(sendSegment, segment, id)
          if seg == null then return closedReason.get().nn
          if seg.getId != id then
            sendersAndClosedFlag.compareAndSet(s + 1, seg.getId * SEGMENT_SIZE)
            () // continue
          else
            val r = finishTrySend(seg, i, s, value)
            if r ne RETRY_SENTINEL then return r
        else
          val r = finishTrySend(seg, i, s, value)
          if r ne RETRY_SENTINEL then return r
        end if
      end if
    end while
    throw new AssertionError("unreachable")
  end trySendOrClosed

  private val RETRY_SENTINEL: AnyRef = new AnyRef

  /** Returns result or RETRY_SENTINEL to indicate the caller should loop. */
  private def finishTrySend(segment: Segment, i: Int, s: Long, value: T): AnyRef =
    val sendResult =
      try updateCellSend(segment, i, s, value, null, null, false)
      catch case e: InterruptedException => throw new AssertionError("unreachable: non-blocking send", e)
    sendResult match
      case SendResult.BUFFERED => null
      case SendResult.RESUMED  =>
        segment.cleanPrev()
        null
      case SendResult.FAILED =>
        segment.cleanPrev()
        RETRY_SENTINEL
      case SendResult.CLOSED                   => closedReason.get()
      case r if r eq Channel.TRY_SEND_NOT_SENT => Channel.TRY_SEND_NOT_SENT
      case _                                   => throw new IllegalStateException(s"Unexpected result: $sendResult")
    end match
  end finishTrySend

  // Non-blocking receive
  override def tryReceiveOrClosed(): AnyRef =
    while true do
      val scf = sendersAndClosedFlag.get()
      val s = getSendersCounter(scf)
      val r = receivers.get()

      if s <= r then
        if isClosed(scf) then return closedForReceive()
        else return null

      val segment = receiveSegment.get()
      if !receivers.compareAndSet(r, r + 1) then () // continue
      else
        val id = r / SEGMENT_SIZE
        val i = (r % SEGMENT_SIZE).toInt

        var seg = segment
        if segment.getId != id then
          seg = findAndMoveForward(receiveSegment, segment, id)
          if seg == null then return closedReason.get().nn
          if seg.getId != id then
            receivers.compareAndSet(r + 1, seg.getId * SEGMENT_SIZE)
            () // continue
          else
            val res = finishTryReceive(seg, i)
            if res ne RETRY_SENTINEL then return res
        else
          val res = finishTryReceive(seg, i)
          if res ne RETRY_SENTINEL then return res
        end if
      end if
    end while
    throw new AssertionError("unreachable")
  end tryReceiveOrClosed

  /** Returns result, null (nothing available), or RETRY_SENTINEL to indicate the caller should loop. */
  private def finishTryReceive(segment: Segment, i: Int): AnyRef | Null =
    val r = receivers.get() - 1 // the cell index we just reserved
    val result =
      try updateCellReceive(segment, i, r, null, null, false)
      catch case e: InterruptedException => throw new AssertionError("unreachable: non-blocking receive", e)
    if result eq ReceiveResult.CLOSED then closedReason.get()
    else if result eq ReceiveResult.FAILED then
      segment.cleanPrev()
      RETRY_SENTINEL
    else if result == null then null
    else
      segment.cleanPrev()
      result
    end if
  end finishTryReceive

  /** Core send logic. Returns SendResult, TRY_SEND_NOT_SENT, or StoredSelectClause. */
  @throws[InterruptedException]
  private def updateCellSend(
      segment: Segment,
      i: Int,
      s: Long,
      value: T,
      select: SelectInstance | Null,
      selectClause: SelectClause[?] | Null,
      suspend: Boolean
  ): AnyRef =
    while true do
      val state = segment.getCell(i)
      if state == null then
        if capacity >= 0 && s >= (if isRendezvous then 0 else bufferEnd.get()) && s >= receivers.get() then
          // no receiver, not in buffer
          if !suspend then
            if segment.casCell(i, null, INTERRUPTED_SEND) then
              segment.cellInterruptedSender()
              return Channel.TRY_SEND_NOT_SENT
          else if select != null then
            val storedSelect = new StoredSelectClause(select, segment, i, true, selectClause.nn, value.asInstanceOf[AnyRef])
            if segment.casCell(i, null, storedSelect) then return storedSelect
          else
            val c = new Continuation(value.asInstanceOf[AnyRef])
            if segment.casCell(i, null, c) then
              if c.await(segment, i, isRendezvous) eq ChannelClosedMarker.CLOSED then return SendResult.CLOSED
              else return SendResult.AWAITED
        else
          // receiver in progress or in buffer -> elimination
          if segment.casCell(i, null, value.asInstanceOf[AnyRef]) then return SendResult.BUFFERED
      else if state eq IN_BUFFER then
        if segment.casCell(i, IN_BUFFER, value.asInstanceOf[AnyRef]) then return SendResult.BUFFERED
      else
        state match
          case c: Continuation =>
            if c.tryResume(value.asInstanceOf[AnyRef]) then
              segment.setCell(i, DONE)
              return SendResult.RESUMED
            else return SendResult.FAILED
          case ss: StoredSelectClause =>
            ss.payload = value.asInstanceOf[AnyRef]
            if ss.select.trySelect(ss) then
              segment.setCell(i, DONE)
              return SendResult.RESUMED
            else return SendResult.FAILED
          case INTERRUPTED_RECEIVE | BROKEN =>
            return SendResult.FAILED
          case CLOSED =>
            return SendResult.CLOSED
          case _ =>
            throw new IllegalStateException(s"Unexpected state: $state in channel: $this")
      end if
    end while
    throw new AssertionError("unreachable")
  end updateCellSend

  // *********
  // Receiving
  // *********

  @throws[InterruptedException]
  override def receive(): T =
    val r = receiveOrClosed()
    r match
      case c: ChannelClosed => throw c.toException()
      case _                => r.asInstanceOf[T]

  @throws[InterruptedException]
  override def receiveOrClosed(): AnyRef =
    doReceive(null, null)

  private def doReceive(select: SelectInstance | Null, selectClause: SelectClause[?] | Null): AnyRef =
    while true do
      val segment = receiveSegment.get()
      val r = receivers.getAndAdd(1L)

      val id = r / SEGMENT_SIZE
      val i = (r % SEGMENT_SIZE).toInt

      var seg = segment
      if segment.getId != id then
        seg = findAndMoveForward(receiveSegment, segment, id)
        if seg == null then return closedReason.get().nn
        if seg.getId != id then
          receivers.compareAndSet(r, seg.getId * SEGMENT_SIZE)
          () // continue
        else
          val result = updateCellReceive(seg, i, r, select, selectClause, true)
          if result eq ReceiveResult.CLOSED then return closedReason.get().nn
          else
            if !result.isInstanceOf[StoredSelectClause] then seg.cleanPrev()
            if result ne ReceiveResult.FAILED then return result
        end if
      else
        val result = updateCellReceive(seg, i, r, select, selectClause, true)
        if result eq ReceiveResult.CLOSED then return closedReason.get().nn
        else
          if !result.isInstanceOf[StoredSelectClause] then seg.cleanPrev()
          if result ne ReceiveResult.FAILED then return result
      end if
    end while
    throw new AssertionError("unreachable")
  end doReceive

  @throws[InterruptedException]
  private def updateCellReceive(
      segment: Segment,
      i: Int,
      r: Long,
      select: SelectInstance | Null,
      selectClause: SelectClause[?] | Null,
      suspend: Boolean
  ): AnyRef =
    while true do
      val state = segment.getCell(i)
      if state == null || (state eq IN_BUFFER) then
        if r >= getSendersCounter(sendersAndClosedFlag.get()) then
          if !suspend then
            if segment.casCell(i, state, INTERRUPTED_RECEIVE) then
              segment.cellInterruptedReceiver()
              expandBuffer()
              return null
          else if select != null then
            val storedSelect = new StoredSelectClause(select, segment, i, false, selectClause.nn, null)
            if segment.casCell(i, state, storedSelect) then
              expandBuffer()
              return storedSelect
          else
            val c = new Continuation(null)
            if segment.casCell(i, state, c) then
              expandBuffer()
              val result = c.await(segment, i, isRendezvous)
              if result eq ChannelClosedMarker.CLOSED then return ReceiveResult.CLOSED
              else return result
        else if segment.casCell(i, state, BROKEN) then
          expandBuffer()
          return ReceiveResult.FAILED
      else
        state match
          case c: Continuation =>
            if segment.casCell(i, state, RESUMING) then
              if c.tryResume(0.asInstanceOf[AnyRef]) then
                segment.setCell(i, DONE)
                expandBuffer()
                return c.payload
              else return ReceiveResult.FAILED
          case ss: StoredSelectClause =>
            if segment.casCell(i, state, RESUMING) then
              if ss.select.trySelect(ss) then
                segment.setCell(i, DONE)
                expandBuffer()
                return ss.payload.asInstanceOf[AnyRef]
              else return ReceiveResult.FAILED
          case cs: CellState =>
            cs match
              case CellState.INTERRUPTED_SEND => return ReceiveResult.FAILED
              case CellState.RESUMING         => Thread.onSpinWait()
              case CellState.CLOSED           => return ReceiveResult.CLOSED
              case _                          => throw new IllegalStateException(s"Unexpected state: $state in channel: $this")
          case _ =>
            // buffered value
            segment.setCell(i, DONE)
            expandBuffer()
            return state.asInstanceOf[AnyRef]
      end if
    end while
    throw new AssertionError("unreachable")
  end updateCellReceive

  // ****************
  // Buffer expansion
  // ****************

  private def expandBuffer(): Unit =
    if capacity <= 0 then return
    while true do
      val segment = bufferEndSegment.get()
      val b = bufferEnd.getAndAdd(1L)

      val id = b / SEGMENT_SIZE
      val i = (b % SEGMENT_SIZE).toInt

      var seg = segment
      if segment.getId != id then
        seg = findAndMoveForward(bufferEndSegment, segment, id)
        if seg == null then return
        if seg.getId != id then
          bufferEnd.compareAndSet(b, seg.getId * SEGMENT_SIZE)
          () // continue - this cell was an interrupted sender
        else
          val result = updateCellExpandBuffer(seg, i)
          if result == ExpandBufferResult.DONE then
            seg.cellProcessed_notInterruptedSender()
            return
          else if result == ExpandBufferResult.CLOSED then
            seg.cellProcessed_notInterruptedSender()
            () // continue to mark other closed cells as processed
        end if
      else
        val result = updateCellExpandBuffer(seg, i)
        if result == ExpandBufferResult.DONE then
          seg.cellProcessed_notInterruptedSender()
          return
        else if result == ExpandBufferResult.CLOSED then
          seg.cellProcessed_notInterruptedSender()
          () // continue
      end if
    end while
  end expandBuffer

  private def updateCellExpandBuffer(segment: Segment, i: Int): ExpandBufferResult =
    while true do
      val state = segment.getCell(i)
      if state == null then
        if segment.casCell(i, null, IN_BUFFER) then return ExpandBufferResult.DONE
      else
        state match
          case DONE                          => return ExpandBufferResult.DONE
          case c: Continuation if c.isSender =>
            if segment.casCell(i, state, RESUMING) then
              if c.tryResume(0.asInstanceOf[AnyRef]) then
                segment.setCell(i, c.payload)
                return ExpandBufferResult.DONE
              else return ExpandBufferResult.FAILED
          case _: Continuation                       => return ExpandBufferResult.DONE
          case ss: StoredSelectClause if ss.isSender =>
            if segment.casCell(i, state, RESUMING) then
              if ss.select.trySelect(ss) then
                segment.setCell(i, ss.payload)
                return ExpandBufferResult.DONE
              else return ExpandBufferResult.FAILED
          case _: StoredSelectClause => return ExpandBufferResult.DONE
          case cs: CellState         =>
            cs match
              case CellState.INTERRUPTED_SEND    => return ExpandBufferResult.FAILED
              case CellState.INTERRUPTED_RECEIVE => return ExpandBufferResult.DONE
              case CellState.BROKEN              => return ExpandBufferResult.DONE
              case CellState.RESUMING            => Thread.onSpinWait()
              case CellState.CLOSED              => return ExpandBufferResult.CLOSED
              case _                             => throw new IllegalStateException(s"Unexpected state: $state in channel: $this")
          case _ =>
            // buffered value
            return ExpandBufferResult.DONE
      end if
    end while
    throw new AssertionError("unreachable")
  end updateCellExpandBuffer

  // *******
  // Closing
  // *******

  override def done(): Unit =
    val r = doneOrClosed()
    r match
      case c: ChannelClosed => throw c.toException()
      case _                =>

  override def doneOrClosed(): AnyRef =
    closeOrClosed(ChannelDone(this))

  override def error(reason: Throwable): Unit =
    if reason == null then throw new NullPointerException("Error reason cannot be null")
    val r = errorOrClosed(reason)
    r match
      case c: ChannelClosed => throw c.toException()
      case _                =>

  override def errorOrClosed(reason: Throwable): AnyRef =
    closeOrClosed(ChannelError(reason, this))

  private def closeOrClosed(cc: ChannelClosed): AnyRef =
    if !closedReason.compareAndSet(null, cc) then return closedReason.get().nn

    // set closed flag
    var scfUpdated = false
    var scf = 0L
    while !scfUpdated do
      val initialScf = sendersAndClosedFlag.get()
      scf = setClosedFlag(initialScf)
      scfUpdated = sendersAndClosedFlag.compareAndSet(initialScf, scf)

    val lastSender = getSendersCounter(scf)
    val lastSegment = sendSegment.get().close()

    cc match
      case _: ChannelError => closeCellsUntil(0, lastSegment)
      case _               => closeCellsUntil(lastSender, lastSegment)

    if capacity > 0 then
      val lastGlobalIndex = (lastSegment.getId + 1) * SEGMENT_SIZE - 1
      while bufferEnd.get() <= lastGlobalIndex do expandBuffer()

    null
  end closeOrClosed

  private def closeCellsUntil(lastCellToClose: Long, segment: Segment | Null): Unit =
    if segment == null then return

    val lastCellToCloseSegmentId = lastCellToClose / SEGMENT_SIZE
    val lastIndexToCloseInSegment =
      if lastCellToCloseSegmentId == segment.getId then (lastCellToClose % SEGMENT_SIZE).toInt
      else if lastCellToCloseSegmentId < segment.getId then 0
      else return

    var i = SEGMENT_SIZE - 1
    while i >= lastIndexToCloseInSegment do
      updateCellClose(segment, i)
      i -= 1

    closeCellsUntil(lastCellToClose, segment.getPrev)
  end closeCellsUntil

  private def updateCellClose(segment: Segment, i: Int): Unit =
    while true do
      val state = segment.getCell(i)
      if state == null || (state eq IN_BUFFER) then
        if segment.casCell(i, state, CLOSED) then
          segment.cellInterruptedReceiver()
          return
      else
        state match
          case c: Continuation =>
            if c.tryResume(ChannelClosedMarker.CLOSED) then
              segment.setCell(i, CLOSED)
              segment.cellInterruptedReceiver()
              return
            else Thread.onSpinWait()
          case ss: StoredSelectClause =>
            if ss.select.channelClosed(closedReason.get().nn) then return
            else Thread.onSpinWait()
          case cs: CellState =>
            cs match
              case CellState.DONE | CellState.BROKEN                          => return
              case CellState.INTERRUPTED_RECEIVE | CellState.INTERRUPTED_SEND => return
              case CellState.RESUMING                                         => Thread.onSpinWait()
              case _ => throw new IllegalStateException(s"Unexpected state: $state in channel: $this")
          case _ =>
            // buffered value: discarding
            if segment.casCell(i, state, CLOSED) then
              segment.cellInterruptedReceiver()
              return
      end if

  override def closedForSend(): ChannelClosed | Null =
    if isClosed(sendersAndClosedFlag.get()) then closedReason.get() else null

  override def closedForReceive(): ChannelClosed | Null =
    if isClosed(sendersAndClosedFlag.get()) then
      val cr = closedReason.get().nn
      cr match
        case _: ChannelError => cr
        case _               => if hasValuesToReceive() then null else cr
    else null

  private def hasValuesToReceive(): Boolean =
    while true do
      val segment = receiveSegment.get()
      val r = receivers.get()
      val s = getSendersCounter(sendersAndClosedFlag.get())
      if s <= r then return false

      val id = r / SEGMENT_SIZE
      val i = (r % SEGMENT_SIZE).toInt

      var seg = segment
      if segment.getId != id then
        seg = findAndMoveForward(receiveSegment, segment, id)
        if seg == null then return false
        if seg.getId != id then
          receivers.compareAndSet(r, seg.getId * SEGMENT_SIZE)
          () // continue
        else
          seg.cleanPrev()
          if hasValueToReceive(seg, i) then return true
          else receivers.compareAndSet(r, r + 1)
      else
        seg.cleanPrev()
        if hasValueToReceive(seg, i) then return true
        else receivers.compareAndSet(r, r + 1)
      end if
    end while
    false
  end hasValuesToReceive

  private def hasValueToReceive(segment: Segment, i: Int): Boolean =
    while true do
      val state = segment.getCell(i)
      if state == null || (state eq IN_BUFFER) then Thread.onSpinWait()
      else
        state match
          case c: Continuation        => return c.isSender
          case ss: StoredSelectClause => return ss.isSender
          case cs: CellState          =>
            cs match
              case CellState.INTERRUPTED_SEND | CellState.INTERRUPTED_RECEIVE => return false
              case CellState.RESUMING                                         => Thread.onSpinWait()
              case CellState.CLOSED                                           => return false
              case CellState.DONE | CellState.BROKEN                          => return false
              case _ => throw new IllegalStateException(s"Unexpected state: $state in channel: $this")
          case _ => return true // buffered value
      end if
    end while
    false
  end hasValueToReceive

  // **************
  // Select clauses
  // **************

  override def receiveClause(): SelectClause[T] = receiveClause(identity)

  override def receiveClause[U](callback: T => U): SelectClause[U] =
    val ch = this
    new SelectClause[U]:
      override private[jox] def getChannel: Channel[?] | Null = ch
      override private[jox] def register(select: SelectInstance): AnyRef =
        try ch.doReceive(select, this)
        catch case e: InterruptedException => throw new IllegalStateException(e)
      override private[jox] def transformedRawValue(rawValue: AnyRef): U =
        callback(rawValue.asInstanceOf[T])
  end receiveClause

  override def sendClause(value: T): SelectClause[Null] = sendClause(value, () => null)

  override def sendClause[U](value: T, callback: () => U): SelectClause[U] =
    val ch = this
    new SelectClause[U]:
      override private[jox] def getChannel: Channel[?] | Null = ch
      override private[jox] def register(select: SelectInstance): AnyRef =
        try
          val result = ch.doSend(value, select, this)
          if result == null then SentClauseMarker.SENT else result
        catch case e: InterruptedException => throw new IllegalStateException(e)
      override private[jox] def transformedRawValue(rawValue: AnyRef): U =
        callback()
    end new
  end sendClause

  private[jox] def cleanupStoredSelectClause(segment: Segment, i: Int, isSender: Boolean): Unit =
    segment.setCell(i, if isSender then INTERRUPTED_SEND else INTERRUPTED_RECEIVE)
    if isSender then segment.cellInterruptedSender()
    else segment.cellInterruptedReceiver()

  // ****
  // Misc
  // ****

  override def toString: String = s"Channel(capacity=$capacity)"

end Channel

object Channel:
  val DEFAULT_BUFFER_SIZE: Int = 16
  val TRY_SEND_NOT_SENT: AnyRef = new AnyRef

  def newRendezvousChannel[T](): Channel[T] = new Channel(0)
  def newBufferedChannel[T](capacity: Int): Channel[T] = new Channel(capacity)
  def newBufferedDefaultChannel[T](): Channel[T] = new Channel(DEFAULT_BUFFER_SIZE)
  def newUnlimitedChannel[T](): Channel[T] = new Channel(-1)

  private val SENDERS_AND_CLOSED_FLAG_SHIFT = 60
  private val SENDERS_COUNTER_MASK = (1L << SENDERS_AND_CLOSED_FLAG_SHIFT) - 1

  private[jox] def getSendersCounter(scf: Long): Long = scf & SENDERS_COUNTER_MASK
  private[jox] def isClosed(scf: Long): Boolean = (scf >> SENDERS_AND_CLOSED_FLAG_SHIFT) == 1
  private[jox] def setClosedFlag(scf: Long): Long = scf | (1L << SENDERS_AND_CLOSED_FLAG_SHIFT)
end Channel
