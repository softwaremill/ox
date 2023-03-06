package ox.channels

import scala.annotation.tailrec

def select[T1, T2](ch1: Source[T1], ch2: Source[T2]): ClosedOr[T1 | T2] = select(List(ch1, ch2))

def selectNow[T1, T2](ch1: Source[T1], ch2: Source[T2]): ClosedOr[Option[T1 | T2]] = selectNow(List(ch1, ch2))

/** Receive an element from exactly one of the channels, if such an element is immediately available. */
@tailrec
def selectNow[T](chs: List[Source[T]]): ClosedOr[Option[T]] =
  chs match
    case Nil => Right(None)
    case ch :: tail =>
      val e = ch.elementPoll()
      e match
        case s: ChannelState.Error    => Left(s)
        case null | ChannelState.Done => selectNow(tail)
        case v: T                     => Right(Some(v))

/** Receive an element from exactly one of the channels, blocking if necessary. Complexity: sum of the waiting queues of the channels. */
def select[T](channels: List[Source[T]]): ClosedOr[T] =
  def cellTakeInterrupted(c: Cell[T], e: InterruptedException): ClosedOr[T] =
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
        case s: ChannelState.Error => Left(s)
        case ChannelState.Done     => select(channels)
        case t: T                  =>
          // received the element; interrupting self and returning it
          try Right(t)
          finally Thread.currentThread().interrupt()

  def takeFromCellInterruptSafe(c: Cell[T]): ClosedOr[T] =
    try
      c.take() match
        case c2: Cell[T]           => offerCellAndTake(c2) // we got a new cell on which we should be waiting, add it to the channels
        case s: ChannelState.Error => Left(s)
        case ChannelState.Done     => select(channels)
        case t: T                  => Right(t)
    catch case e: InterruptedException => cellTakeInterrupted(c, e)
    // now that the cell has been filled, it is owned, and should be removed from the waiting lists of the other channels
    finally cleanupCell(c, alsoWhenSingleChannel = false)

  def cleanupCell(cell: Cell[T], alsoWhenSingleChannel: Boolean): Unit =
    if channels.length > 1 || alsoWhenSingleChannel then channels.foreach(_.cellCleanup(cell))

  @tailrec
  def elementExists_verifyNotClosed(chs: List[Source[T]], allDone: Boolean, cell: Cell[T]): ClosedOr[Boolean] =
    chs match
      case Nil if allDone =>
        // either the cell is already taken off one of the waiting queues & being completed with a done,
        // or it's never going to get handled; either way, we're never .take-ing from this cell
        cleanupCell(cell, alsoWhenSingleChannel = true)
        Left(ChannelState.Done)
      case Nil => Right(false)
      case c :: tail =>
        c.elementPeek() match
          case s: ChannelState.Error =>
            if cell.tryOwn() then
              // nobody else will complete the cell, we can safely remove it
              cleanupCell(cell, alsoWhenSingleChannel = true)
              Left(s)
            else
              // somebody already owned that cell; continuing with the normal process of .take-ing from it
              Right(false)
          case ChannelState.Done => elementExists_verifyNotClosed(tail, allDone, cell)
          case null              => elementExists_verifyNotClosed(tail, false, cell)
          case _                 => Right(true)

  def offerCellAndTake(c: Cell[T]): ClosedOr[T] =
    channels.foreach(_.cellOffer(c))

    // check, if no new element has arrived in the meantime (possibly, before we added the cell)
    // plus, verify that none of the channels is in an error state, and that not all channels are closed
    elementExists_verifyNotClosed(channels, allDone = true, c).flatMap {
      case true =>
        // some element arrived in the meantime: trying to invalidate the cell by owning it
        if c.tryOwn() then
          // We managed to complete the cell before any other thread. We are sure that there's nobody waiting on this
          // cell, as this could only be us.
          // First, we need to remove the now stale cell from the channels' waiting lists. Even if there's only one
          // channel - as we completed the cell, nobody ever dequeued it.
          cleanupCell(c, alsoWhenSingleChannel = true)
          // Try to obtain an element again
          select(channels)
        else
          // some other thread already completed the cell - receiving the element
          takeFromCellInterruptSafe(c)
      case false =>
        // still no new elements - waiting for one to arrive
        takeFromCellInterruptSafe(c)
    }

  selectNow(channels).flatMap {
    case Some(e) => Right(e)
    case None => offerCellAndTake(Cell[T]) // none of the channels has an available element - enqueue a cell on each channel's waiting list
  }
