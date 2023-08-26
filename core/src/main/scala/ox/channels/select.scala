package ox.channels

import scala.annotation.tailrec

def select(clause1: SelectClause[_], clause2: SelectClause[_]): clause1.Result | clause2.Result | ChannelClosed =
  select(List(clause1, clause2)).asInstanceOf[clause1.Result | clause2.Result | ChannelClosed]

def select(
    clause1: SelectClause[_],
    clause2: SelectClause[_],
    clause3: SelectClause[_]
): clause1.Result | clause2.Result | clause3.Result | ChannelClosed =
  select(List(clause1, clause2, clause3)).asInstanceOf[clause1.Result | clause2.Result | clause3.Result | ChannelClosed]

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

//

private def doSelect[T](clauses: List[SelectClause[T]]): SelectResult[T] | ChannelClosed =
  def takeFromCellInterruptSafe(c: Cell[T], cleanUpForClauses: List[SelectClause[T]]): SelectResult[T] | ChannelClosed =
    try
      c.take() match
        case c2: Cell[T] @unchecked => offerCellAndTake(c2) // we got a new cell on which we should be waiting, add it to the channels
        case s: ChannelState.Error  => ChannelClosed.Error(s.reason)
        case ChannelState.Done      => doSelect(clauses)
        case t: SelectResult[T] @unchecked      => t
        case t: MaybeCreateResult[T] @unchecked =>
          // this might throw exceptions, but this is fine - we're on the thread that called select
          t() match
            case Some(r) => r
            case None    => doSelect(clauses)
    catch case e: InterruptedException => cellTakeInterrupted(c, e)
    // now that the cell has been filled, it is owned, and should be removed from the waiting lists of the other channels
    finally cleanupCell(cleanUpForClauses, c, alsoWhenSingleClause = false)

  def offerCellAndTake(c: Cell[T]): SelectResult[T] | ChannelClosed =
    offerAndTrySatisfy(clauses, c, allDone = true, Nil) match {
      case ((), offeredTo)               => takeFromCellInterruptSafe(c, offeredTo)
      case (r: ChannelClosed, offeredTo) =>
        // Checking, if the cell was still present on all queues, on which we put it, and only if that's the case,
        // completing the cell with the return closed state.
        // It might happen that the cell was already taken off the queue by another process, and that process will
        // try to complete it with an element. This might have prevented our call to `trySatisfyWaiting()` from
        // successfully completing the cell.
        val cleanedUpEverywhere = cleanupCell(offeredTo, c, alsoWhenSingleClause = true)

        // If we've taken the cell out of each queue, then for sure it's not yet owned. Owning anyway for consistency.
        // If it's already taken off some queue, that process will try to own it.
        if cleanedUpEverywhere && c.tryOwn() then r
        else takeFromCellInterruptSafe(c, Nil) // already cleaned up
    }

  def default: Option[Default[T]] =
    clauses.collect { case d: Default[T] => d } match
      case Nil      => None
      case d :: Nil => Some(d)
      case ds       => throw new IllegalArgumentException(s"More than one default clause in select: $ds")

  default match
    case None => offerCellAndTake(Cell[T])
    case Some(d) =>
      val c = Cell[T]
      trySatisfyNow(clauses, c, allDone = true) match
        case (ChannelClosed.Done, true)  => ChannelClosed.Done
        case (ChannelClosed.Done, false) => DefaultResult(d.value)
        case (e: ChannelClosed.Error, _) => e
        case false                       => DefaultResult(d.value)
        case true                        =>
          // the cell has been satisfied, it shouldn't yet be owned; owning it in case take() is interrupted
          if !c.tryOwn() then throw IllegalStateException()
          takeFromCellInterruptSafe(c, Nil) // the cell hasn't been offered to any channel

//

def cellTakeInterrupted[T](c: Cell[T], e: InterruptedException): SelectResult[T] | ChannelClosed =
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
      case t: MaybeCreateResult[T] @unchecked =>
        try
          t() match
            case Some(r) => r
            case None    => throw e
        finally Thread.currentThread().interrupt()

/** @return `true` if the cell was removed from all queues; `false` if there was at least one queue, from which the cell was absent */
def cleanupCell[T](from: List[SelectClause[T]], cell: Cell[T], alsoWhenSingleClause: Boolean): Boolean =
  if from.length > 1 || alsoWhenSingleClause then
    from
      .map {
        case s: Source[_]#Receive               => s.channel.receiveCellCleanup(cell)
        case s: BufferedChannel[_]#BufferedSend => s.channel.sendCellCleanup(cell)
        case s: DirectChannel[_]#DirectSend     => s.channel.sendCellCleanup(cell)
        case _: Default[_]                      => true
      }
      // performing a .map+.forall, instead of just .forall, as we want all cleanups to run, even if some return `false`
      .forall(identity)
  else true

@tailrec def offerAndTrySatisfy[T](
    ccs: List[SelectClause[T]],
    c: Cell[T],
    allDone: Boolean,
    offeredTo: List[SelectClause[T]]
): (Unit | ChannelClosed, List[SelectClause[T]]) =
  ccs match
    case Nil if allDone => (ChannelClosed.Done, offeredTo)
    case Nil            => ((), offeredTo)
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
        case _: Default[_] => throw new IllegalStateException() // should use trySatisfyNow() instead

      def skipWhenDone = clause match
        case s: Source[_]#Receive => s.skipWhenDone
        case _                    => true

      clauseTrySatisfyResult match
        case ChannelClosed.Error(e)             => (ChannelClosed.Error(e), offeredTo)
        case ChannelClosed.Done if skipWhenDone => offerAndTrySatisfy(tail, c, allDone, clause :: offeredTo)
        case ChannelClosed.Done                 => (ChannelClosed.Done, offeredTo)
        // optimization: checking if the cell is already owned; if so, no need to put it on other queues
        // TODO: this might be possibly further optimized, by returning the satisfied cells from trySatisfyWaiting()
        // TODO: and then checking if the cell is already satisfied, instead of looking at the AtomicBoolean
        case () if c.isAlreadyOwned => ((), offeredTo)
        case ()                     => offerAndTrySatisfy(tail, c, false, clause :: offeredTo)

/** @return `true` if the cell has been (immediately) satisfied (without owning it). */
@tailrec def trySatisfyNow[T](
    ccs: List[SelectClause[T]],
    c: Cell[T],
    allDone: Boolean
): Boolean | (ChannelClosed, Boolean) =
  ccs match
    case Nil if allDone => (ChannelClosed.Done, false)
    case Nil            => false
    case clause :: tail =>
      val clauseTrySatisfyResult = clause match
        case s: Source[_]#Receive               => s.channel.trySatisfyReceive(c)
        case s: BufferedChannel[_]#BufferedSend => s.channel.trySatisfySend(s.v, c)
        case s: DirectChannel[_]#DirectSend     => s.channel.trySatisfySend(s.v, c)
        case _: Default[_]                      => false

      def skipWhenDone = clause match
        case s: Source[_]#Receive => s.skipWhenDone
        case _                    => true

      clauseTrySatisfyResult match
        case ChannelClosed.Error(e)             => (ChannelClosed.Error(e), true)
        case ChannelClosed.Done if skipWhenDone => trySatisfyNow(tail, c, allDone)
        case ChannelClosed.Done                 => (ChannelClosed.Done, true)
        case true                               => true
        case false                              => trySatisfyNow(tail, c, false)
