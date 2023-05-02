package ox.channels

import scala.annotation.tailrec
import scala.util.Random

def select[T1, T2](ch1: Source[T1], ch2: Source[T2]): ChannelResult[T1 | T2] = select(List(ch1, ch2))

def selectNow[T1, T2](ch1: Source[T1], ch2: Source[T2]): ChannelResult[Option[T1 | T2]] = selectNow(List(ch1, ch2))

/** Receive an element from exactly one of the channels, if such an element is immediately available. */
def selectNow[T](chs: List[Source[T]]): ChannelResult[Option[T]] = doSelectNow(Random.shuffle(chs))

@tailrec
private def doSelectNow[T](chs: List[Source[T]]): ChannelResult[Option[T]] =
  chs match
    case Nil => ChannelResult.Value(None)
    case ch :: tail =>
      val e = ch.elementPoll()
      e match
        case s: ChannelState.Error    => ChannelResult.Error(s.reason)
        case null | ChannelState.Done => doSelectNow(tail)
        case v: T                     => ChannelResult.Value(Some(v))

/** Receive an element from exactly one of the channels, blocking if necessary. Complexity: sum of the waiting queues of the channels. */
def select[T](channels: List[Source[T]]): ChannelResult[T] =
  // randomizing the order of the channels to ensure fairness: not the mos efficient solution, but will have to do for now
  doSelect(Random.shuffle(channels))

private def doSelect[T](channels: List[Source[T]]): ChannelResult[T] =
  def cellTakeInterrupted(c: Cell[T], e: InterruptedException): ChannelResult[T] =
    // trying to invalidate the cell by owning it
    if c.tryOwn() then
      // nobody else will complete the cell, we can re-throw the exception
      throw e
    else
      // somebody else completed the cell; might block, but even if, only for a short period of time, as the
      // cell-owning thread should complete it without blocking
      c.take() match
        case _: Cell[T] =>
          // nobody else will complete the new cell, as it's not put on the channels waiting queues, we can re-throw the exception
          throw e
        case s: ChannelState.Error => ChannelResult.Error(s.reason)
        case ChannelState.Done     => doSelect(channels)
        case t: T                  =>
          // received the element; interrupting self and returning it
          try ChannelResult.Value(t)
          finally Thread.currentThread().interrupt()

  def takeFromCellInterruptSafe(c: Cell[T]): ChannelResult[T] =
    try
      c.take() match
        case c2: Cell[T]           => offerCellAndTake(c2) // we got a new cell on which we should be waiting, add it to the channels
        case s: ChannelState.Error => ChannelResult.Error(s.reason)
        case ChannelState.Done     => doSelect(channels)
        case t: T                  => ChannelResult.Value(t)
    catch case e: InterruptedException => cellTakeInterrupted(c, e)
    // now that the cell has been filled, it is owned, and should be removed from the waiting lists of the other channels
    finally cleanupCell(c, alsoWhenSingleChannel = false)

  def cleanupCell(cell: Cell[T], alsoWhenSingleChannel: Boolean): Unit =
    if channels.length > 1 || alsoWhenSingleChannel then channels.foreach(_.cellCleanup(cell))

  @tailrec
  def elementExists_verifyNotClosed(chs: List[Source[T]], allDone: Boolean, cell: Cell[T]): ChannelResult[Boolean] =
    chs match
      case Nil if allDone => ChannelResult.Done
      case Nil            => ChannelResult.Value(false)
      case c :: tail =>
        c.elementPeek() match
          case s: ChannelState.Error =>
            if cell.tryOwn() then
              // nobody else will complete the cell, we can safely remove it
              cleanupCell(cell, alsoWhenSingleChannel = true)
              ChannelResult.Error(s.reason)
            else
              // somebody already owned that cell; continuing with the normal process of .take-ing from it
              ChannelResult.Value(false)
          case ChannelState.Done => elementExists_verifyNotClosed(tail, allDone, cell)
          case null              => elementExists_verifyNotClosed(tail, false, cell)
          case _                 => ChannelResult.Value(true)

  def offerCellAndTake(c: Cell[T]): ChannelResult[T] =
    channels.foreach(_.cellOffer(c))

    // check, if no new element has arrived in the meantime (possibly, before we added the cell)
    // plus, verify that none of the channels is in an error state, and that not all channels are closed
    elementExists_verifyNotClosed(channels, allDone = true, c) match {
      case ChannelResult.Value(true) =>
        // some element arrived in the meantime: trying to invalidate the cell by owning it
        if c.tryOwn() then
          // We managed to complete the cell before any other thread. We are sure that there's nobody waiting on this
          // cell, as this could only be us.
          // First, we need to remove the now stale cell from the channels' waiting lists, even if there's only one
          // channel: we owned the cell, so we can't know if anybody ever dequeued it.
          cleanupCell(c, alsoWhenSingleChannel = true)
          // Try to obtain an element again
          doSelect(channels)
        else
          // some other thread already completed the cell - receiving the element
          takeFromCellInterruptSafe(c)
      case ChannelResult.Value(false) =>
        // still no new elements - waiting for one to arrive
        takeFromCellInterruptSafe(c)
      case r: ChannelResult.Closed =>
        // either the cell is already taken off one of the waiting queues & being completed, or it's never going to get handled
        if c.tryOwn() then
          // nobody else will complete the cell, we can safely remove it
          cleanupCell(c, alsoWhenSingleChannel = true)
          r
        else takeFromCellInterruptSafe(c)
    }

  doSelectNow(channels).flatMap {
    case Some(e) => ChannelResult.Value(e)
    case None => offerCellAndTake(Cell[T]) // none of the channels has an available element - enqueue a cell on each channel's waiting list
  }
