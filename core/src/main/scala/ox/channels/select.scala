package ox.channels

import scala.annotation.tailrec
import scala.util.Random

def select(clause1: ChannelClause[_], clause2: ChannelClause[_]): ChannelResult[clause1.Result | clause2.Result] =
  select(List(clause1, clause2)).asInstanceOf[ChannelResult[clause1.Result | clause2.Result]]

def select[T](clauses: List[ChannelClause[T]]): ChannelResult[ChannelClauseResult[T]] =
  // randomizing the order of the channels to ensure fairness: not the most efficient solution, but will have to do for now
  doSelect(Random.shuffle(clauses))

//def select[T1, T2](ch1: Source[T1], ch2: Source[T2]): ChannelResult[T1 | T2] = select(List(ch1, ch2))

private def doSelect[T](clauses: List[ChannelClause[T]]): ChannelResult[ChannelClauseResult[T]] =
  def cellTakeInterrupted(c: Cell[T], e: InterruptedException): ChannelResult[ChannelClauseResult[T]] =
    // trying to invalidate the cell by owning it
    if c.tryOwn() then
      // nobody else will complete the cell, we can re-throw the exception
      throw e
    else
      // somebody else completed the cell; might block, but even if, only for a short period of time, as the
      // cell-owning thread should complete it without blocking
      c.take() match
        case _: Cell[T] @unchecked =>
          // nobody else will complete the new cell, as it's not put on the channels waiting queues, we can re-throw the exception
          throw e
        case s: ChannelState.Error => ChannelResult.Error(s.reason)
        case ChannelState.Done     =>
          // one of the channels is done, others might be not, but we simply re-throw the exception
          throw e
        case t: ChannelClauseResult[T] @unchecked =>
          // completed with a value; interrupting self and returning it
          try ChannelResult.Value(t)
          finally Thread.currentThread().interrupt()

  def takeFromCellInterruptSafe(c: Cell[T]): ChannelResult[ChannelClauseResult[T]] =
    try
      c.take() match
        case c2: Cell[T] @unchecked => offerCellAndTake(c2) // we got a new cell on which we should be waiting, add it to the channels
        case s: ChannelState.Error  => ChannelResult.Error(s.reason)
        case ChannelState.Done      => doSelect(clauses)
        case t: ChannelClauseResult[T] @unchecked => ChannelResult.Value(t)
    catch case e: InterruptedException => cellTakeInterrupted(c, e)
    // now that the cell has been filled, it is owned, and should be removed from the waiting lists of the other channels
    finally cleanupCell(c, alsoWhenSingleClause = false)

  def cleanupCell(cell: Cell[T], alsoWhenSingleClause: Boolean): Unit =
    if clauses.length > 1 || alsoWhenSingleClause then
      clauses.foreach {
        case s: Source[_]#Receive               => s.channel.receiveCellCleanup(cell)
        case s: BufferedChannel[_]#BufferedSend => s.channel.sendCellCleanup(cell)
        case s: DirectChannel[_]#DirectSend     => s.channel.sendCellCleanup(cell)
      }

  @tailrec
  def trySatisfyWaiting(ccs: List[ChannelClause[T]], allDone: Boolean): ChannelResult[Unit] =
    ccs match
      case Nil if allDone => ChannelResult.Done
      case Nil            => ChannelResult.Value(())
      case clause :: tail =>
        val clauseTrySatisfyResult = clause.channel match
          case s: Source[_] => s.trySatisfyWaiting()
          case s: Sink[_]   => s.trySatisfyWaiting()

        clauseTrySatisfyResult match
          case ChannelResult.Error(e) => ChannelResult.Error(e)
          case ChannelResult.Done     => trySatisfyWaiting(tail, allDone)
          case _                      => trySatisfyWaiting(tail, false)

  def offerCellAndTake(c: Cell[T]): ChannelResult[ChannelClauseResult[T]] =
    clauses.foreach {
      case s: Source[_]#Receive               => s.channel.receiveCellOffer(c)
      case s: BufferedChannel[_]#BufferedSend => s.channel.sendCellOffer(s.v, c)
      case s: DirectChannel[_]#DirectSend     => s.channel.sendCellOffer(s.v, c)
    }

    trySatisfyWaiting(clauses, allDone = true) match {
      case ChannelResult.Value(()) => takeFromCellInterruptSafe(c)
      case r: ChannelResult.Closed =>
        // either the cell is already taken off one of the waiting queues & being completed, or it's never going to get handled
        if c.tryOwn() then
          // nobody else will complete the cell, we can safely remove it
          cleanupCell(c, alsoWhenSingleClause = true)
          r
        else takeFromCellInterruptSafe(c)
    }

  offerCellAndTake(Cell[T])
