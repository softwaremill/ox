package ox.channels

import scala.annotation.tailrec
import scala.util.Random

def select(clause1: SelectClause[_], clause2: SelectClause[_]): clause1.Result | clause2.Result | ChannelClosed =
  select(List(clause1, clause2)).asInstanceOf[clause1.Result | clause2.Result | ChannelClosed]

def select(
    clause1: SelectClause[_],
    clause2: SelectClause[_],
    clause3: SelectClause[_]
): clause1.Result | clause2.Result | clause3.Result | ChannelClosed =
  select(List(clause1, clause2)).asInstanceOf[clause1.Result | clause2.Result | clause3.Result | ChannelClosed]

def select[T1, T2](source1: Source[T1], source2: Source[T2]): T1 | T2 | ChannelClosed =
  select(source1.receiveClause, source2.receiveClause).map {
    case source1.Received(v) => v
    case source2.Received(v) => v
  }

def select[T1, T2, T3](source1: Source[T1], source2: Source[T2], source3: Source[T3]): T1 | T2 | T3 | ChannelClosed =
  select(source1.receiveClause, source2.receiveClause, source3.receiveClause).map {
    case source1.Received(v) => v
    case source2.Received(v) => v
    case source3.Received(v) => v
  }

/** The select is biased towards the clauses first on the list. To ensure fairness, you might want to randomize the clause order using
  * {{{Random.shuffle(clauses)}}}.
  */
def select[T](clauses: List[SelectClause[T]]): SelectResult[T] | ChannelClosed = doSelect(clauses)

def select[T](sources: List[Source[T]])(using DummyImplicit): T | ChannelClosed =
  doSelect(sources.map(_.receiveClause: SelectClause[T])) match
    case r: Source[T]#Received => r.value
    case c: ChannelClosed      => c
    case _: Sink[_]#Sent       => throw new IllegalStateException()
    case _: DefaultResult[_]   => throw new IllegalStateException()

private def doSelect[T](clauses: List[SelectClause[T]]): SelectResult[T] | ChannelClosed =
  def cellTakeInterrupted(c: Cell[T], e: InterruptedException): SelectResult[T] | ChannelClosed =
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
        case s: ChannelState.Error => ChannelClosed.Error(s.reason)
        case ChannelState.Done     =>
          // one of the channels is done, others might be not, but we simply re-throw the exception
          throw e
        case t: SelectResult[T] @unchecked =>
          // completed with a value; interrupting self and returning it
          try t
          finally Thread.currentThread().interrupt()

  def takeFromCellInterruptSafe(c: Cell[T]): SelectResult[T] | ChannelClosed =
    try
      c.take() match
        case c2: Cell[T] @unchecked => offerCellAndTake(c2) // we got a new cell on which we should be waiting, add it to the channels
        case s: ChannelState.Error  => ChannelClosed.Error(s.reason)
        case ChannelState.Done      => doSelect(clauses)
        case t: SelectResult[T] @unchecked => t
    catch case e: InterruptedException => cellTakeInterrupted(c, e)
    // now that the cell has been filled, it is owned, and should be removed from the waiting lists of the other channels
    finally cleanupCell(c, alsoWhenSingleClause = false)

  def cleanupCell(cell: Cell[T], alsoWhenSingleClause: Boolean): Unit =
    if clauses.length > 1 || alsoWhenSingleClause then
      clauses.foreach {
        case s: Source[_]#Receive               => s.channel.receiveCellCleanup(cell)
        case s: BufferedChannel[_]#BufferedSend => s.channel.sendCellCleanup(cell)
        case s: DirectChannel[_]#DirectSend     => s.channel.sendCellCleanup(cell)
        case _: Default[_]                      => ()
      }

  @tailrec def offerAndTrySatisfy(ccs: List[SelectClause[T]], c: Cell[T], allDone: Boolean): Unit | ChannelClosed =
    ccs match
      case Nil if allDone => ChannelClosed.Done
      case Nil            => ()
      case clause :: tail =>
        val clauseTrySatisfyResult = clause match
          case s: Source[_]#Receive =>
            s.channel.receiveCellOffer(c)
            s.channel.trySatisfyWaiting()
          case s: BufferedChannel[_]#BufferedSend =>
            s.channel.sendCellOffer(s.v, c)
            s.channel.trySatisfyWaiting()
          case s: DirectChannel[_]#DirectSend =>
            s.channel.sendCellOffer(s.v, c)
            s.channel.trySatisfyWaiting()
          case _: Default[_] => ()

        clauseTrySatisfyResult match
          case ChannelClosed.Error(e) => ChannelClosed.Error(e)
          case ChannelClosed.Done     => offerAndTrySatisfy(tail, c, allDone)
          // optimization: checking if the cell is already owned; if so, no need to put it on other queues
          // TODO: this might be possibly further optimized, by returning the satisfied cells from trySatisfyWaiting()
          // TODO: and then checking if the cell is already satisfied, instead of looking at the AtomicBoolean
          case () if c.isAlreadyOwned => ()
          case ()                     => offerAndTrySatisfy(tail, c, false)

  def offerCellAndTake(c: Cell[T]): SelectResult[T] | ChannelClosed =
    offerAndTrySatisfy(clauses, c, allDone = true) match {
      case ()               => takeFromCellInterruptSafe(c)
      case r: ChannelClosed =>
        // either the cell is already taken off one of the waiting queues & being completed, or it's never going to get handled
        if c.tryOwn() then
          // nobody else will complete the cell, we can safely remove it
          // TODO: only cleanup the cell from channels to which it has been actually added - see the optimization above
          cleanupCell(c, alsoWhenSingleClause = true)
          r
        else takeFromCellInterruptSafe(c)
    }

  def offerCellAndTakeWithDefault(c: Cell[T], d: Default[T]): SelectResult[T] | ChannelClosed =
    offerAndTrySatisfy(clauses, c, allDone = true) match {
      case () =>
        if c.tryOwn() then
          // the cell wasn't immediately satisfied, so returning the default value
          cleanupCell(c, alsoWhenSingleClause = true)
          DefaultResult(d.value)
        else
          // the cell is already owned, a result should be available shortly
          takeFromCellInterruptSafe(c)

      case r: ChannelClosed =>
        // same as in offerCellAndTake
        if c.tryOwn() then
          cleanupCell(c, alsoWhenSingleClause = true)
          r
        else takeFromCellInterruptSafe(c)
    }

  def default: Option[Default[T]] =
    clauses.collect { case d: Default[T] => d } match
      case Nil      => None
      case d :: Nil => Some(d)
      case ds       => throw new IllegalArgumentException(s"More than one default clause in select: $ds")

  default match
    case None    => offerCellAndTake(Cell[T])
    case Some(d) => offerCellAndTakeWithDefault(Cell[T], d)
