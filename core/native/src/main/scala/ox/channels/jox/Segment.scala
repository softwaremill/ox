package ox.channels.jox

import java.util.concurrent.atomic.{AtomicInteger, AtomicReference, AtomicReferenceArray}

final class Segment(
    private val id: Long,
    initialPrev: Segment | Null,
    pointers: Int,
    val isRendezvousOrUnlimited: Boolean
):
  import Segment.*

  private val data = new AtomicReferenceArray[AnyRef](SEGMENT_SIZE)
  private val nextRef = new AtomicReference[Segment | Null](null)
  private val prevRef = new AtomicReference[Segment | Null](initialPrev)

  // bits: [pointers(2)][notProcessed(6)][notInterrupted(6)]
  private val pointersNotProcessedNotInterrupted = new AtomicInteger(
    SEGMENT_SIZE
      + (if isRendezvousOrUnlimited then 0 else SEGMENT_SIZE << PROCESSED_SHIFT)
      + (pointers << POINTERS_SHIFT)
  )

  def getId: Long = id

  def cleanPrev(): Unit = prevRef.set(null)

  def getNext: Segment | Null =
    val s = nextRef.get()
    if s eq CLOSED_SENTINEL then null else s

  def getPrev: Segment | Null = prevRef.get()

  private def setNextIfNull(setTo: Segment): Boolean =
    nextRef.compareAndSet(null, setTo)

  def getCell(index: Int): AnyRef | Null = data.get(index)

  def setCell(index: Int, value: AnyRef): Unit = data.set(index, value)

  def casCell(index: Int, expected: AnyRef | Null, newValue: AnyRef): Boolean =
    data.compareAndSet(index, expected.asInstanceOf[AnyRef], newValue)

  private def isTail: Boolean = getNext == null

  def isRemoved: Boolean = pointersNotProcessedNotInterrupted.get() == 0

  def tryIncPointers(): Boolean =
    var p = pointersNotProcessedNotInterrupted.get()
    while p != 0 do
      if pointersNotProcessedNotInterrupted.compareAndSet(p, p + (1 << POINTERS_SHIFT)) then return true
      p = pointersNotProcessedNotInterrupted.get()
    false

  def decPointers(): Boolean =
    val toAdd = -(1 << POINTERS_SHIFT)
    var currentP = pointersNotProcessedNotInterrupted.get()
    while true do
      if pointersNotProcessedNotInterrupted.compareAndSet(currentP, currentP + toAdd) then return (currentP + toAdd) == 0
      currentP = pointersNotProcessedNotInterrupted.get()
    false // unreachable

  def cellInterruptedReceiver(): Unit =
    if pointersNotProcessedNotInterrupted.getAndDecrement() == 1 then remove()

  def cellInterruptedSender(): Unit =
    if isRendezvousOrUnlimited then
      if pointersNotProcessedNotInterrupted.getAndDecrement() == 1 then remove()
    else if pointersNotProcessedNotInterrupted.getAndAdd(-ONE_PROCESSED_AND_INTERRUPTED) == ONE_PROCESSED_AND_INTERRUPTED then remove()

  def cellProcessed_notInterruptedSender(): Unit =
    if pointersNotProcessedNotInterrupted.getAndAdd(-ONE_PROCESSED) == ONE_PROCESSED then remove()

  /** Marks cells as processed during channel setup. Not thread-safe. */
  def setup_markCellsProcessed(numberOfCells: Int): Unit =
    pointersNotProcessedNotInterrupted.addAndGet(-ONE_PROCESSED * numberOfCells)
    ()

  def remove(): Unit =
    var continue = true
    while continue do
      if isTail then return
      val _prev = aliveSegmentLeft()
      val _next = aliveSegmentRight()

      // link next.prev to _prev
      var prevOfNextUpdated = false
      while !prevOfNextUpdated do
        val currentPrevOfNext = _next.prevRef.get()
        if currentPrevOfNext == null then prevOfNextUpdated = true
        else prevOfNextUpdated = _next.prevRef.compareAndSet(currentPrevOfNext, _prev)

      if _prev != null then _prev.nextRef.set(_next)

      if _next.isRemoved && !_next.isTail then () // continue loop
      else if _prev != null && _prev.isRemoved then () // continue loop
      else continue = false
    end while
  end remove

  def close(): Segment =
    var s: Segment = this
    while true do
      val n = s.nextRef.get()
      if n == null then
        if s.nextRef.compareAndSet(null, CLOSED_SENTINEL) then return s
      else if n eq CLOSED_SENTINEL then return s
      else s = n
    s // unreachable
  end close

  private def aliveSegmentLeft(): Segment | Null =
    var s = prevRef.get()
    while s != null && s.isRemoved do s = s.prevRef.get()
    s

  private def aliveSegmentRight(): Segment =
    var n = nextRef.get()
    while n.nn.isRemoved && !n.nn.isTail do n = n.nn.nextRef.get()
    n.nn

  // for tests
  def setNext(newNext: Segment | Null): Unit = nextRef.set(newNext)

  override def toString: String =
    val n = nextRef.get()
    val p = prevRef.get()
    val c = pointersNotProcessedNotInterrupted.get()
    val notInterrupted = c & ((1 << PROCESSED_SHIFT) - 1)
    val notProcessed = (c & ((1 << POINTERS_SHIFT) - 1)) >> PROCESSED_SHIFT
    val ptrs = c >> POINTERS_SHIFT
    val nextStr = if n == null then "null" else if n eq CLOSED_SENTINEL then "closed" else n.id.toString
    val prevStr = if p == null then "null" else p.id.toString
    s"Segment{id=$id, next=$nextStr, prev=$prevStr, pointers=$ptrs, notProcessed=$notProcessed, notInterrupted=$notInterrupted}"
  end toString

end Segment

object Segment:
  val SEGMENT_SIZE: Int =
    val env = System.getenv("JOX_SEGMENT_SIZE")
    if env != null then Integer.parseInt(env) else 32

  private val PROCESSED_SHIFT = 6
  private val POINTERS_SHIFT = 12
  private val ONE_PROCESSED = 1 << PROCESSED_SHIFT
  private val ONE_PROCESSED_AND_INTERRUPTED = ONE_PROCESSED + 1

  val NULL_SEGMENT: Segment = new Segment(-1, null, 0, false)
  private val CLOSED_SENTINEL: Segment = new Segment(-1, null, 0, false)

  /** Finds or creates a non-removed segment with id >= `id`, and updates `ref` to it. */
  def findAndMoveForward(ref: AtomicReference[Segment], start: Segment, id: Long): Segment | Null =
    var continue = true
    while continue do
      val segment = findSegment(start, id)
      if segment == null then return null
      if moveForward(ref, segment) then return segment
    null // unreachable

  private def findSegment(start: Segment, id: Long): Segment | Null =
    var current = start
    while current.getId < id || current.isRemoved do
      val n = current.nextRef.get()
      if n eq CLOSED_SENTINEL then return null
      else if n == null then
        val newSegment = new Segment(current.getId + 1, current, 0, start.isRendezvousOrUnlimited)
        if current.setNextIfNull(newSegment) then if current.isRemoved then current.remove()
      else current = n.nn
    current
  end findSegment

  private def moveForward(ref: AtomicReference[Segment], to: Segment): Boolean =
    while true do
      val current = ref.get()
      if current.getId >= to.getId then return true
      if !to.tryIncPointers() then return false
      if ref.compareAndSet(current, to) then
        if current.decPointers() then current.remove()
        return true
      else if to.decPointers() then to.remove()
    end while
    false // unreachable
  end moveForward
end Segment
