package ox.channels.jox

import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport
import scala.collection.mutable

object Select:
  @throws[InterruptedException]
  def select[U](clauses: SelectClause[? <: U]*): U =
    val r = selectOrClosed(clauses*)
    r match
      case c: ChannelClosed => throw c.toException()
      case _                => r.asInstanceOf[U]

  @throws[InterruptedException]
  def selectOrClosed[U](clauses: SelectClause[? <: U]*): AnyRef =
    if clauses == null || clauses.isEmpty then throw new IllegalArgumentException("No clauses given")
    if clauses.exists(_ == null) then throw new IllegalArgumentException("Null clauses are not supported")
    while true do
      val r = doSelectOrClosed(clauses*)
      if r ne RestartSelectMarker.RESTART then return r
    throw new AssertionError("unreachable")

  @throws[InterruptedException]
  def defaultClause[T](value: T): SelectClause[T] = new DefaultClauseValue(value)

  def defaultClause[T](callback: () => T): SelectClause[T] = new DefaultClauseCallback(callback)

  @throws[InterruptedException]
  private def doSelectOrClosed[U](clauses: SelectClause[? <: U]*): AnyRef =
    // short-circuit if any channel is in error
    val anyError = getAnyChannelInError(clauses)
    if anyError != null then return anyError

    val allRendezvous = verifyChannelsUnique_getAreAllRendezvous(clauses)
    val si = new SelectInstance(clauses.size)
    var i = 0
    var done = false
    while i < clauses.size && !done do
      val clause = clauses(i)
      clause match
        case _: DefaultClause[?] if i != clauses.size - 1 =>
          throw new IllegalArgumentException("The default clause can only be the last one.")
        case _ =>
      if !si.register(clause) then done = true
      i += 1
    si.checkStateAndWait(allRendezvous)
  end doSelectOrClosed

  private def verifyChannelsUnique_getAreAllRendezvous(clauses: Seq[SelectClause[?]]): Boolean =
    var allRendezvous = true
    var i = 0
    while i < clauses.size do
      val chi = clauses(i).getChannel
      var j = i + 1
      while j < clauses.size do
        if (chi ne null) && (chi eq clauses(j).getChannel) then
          throw new IllegalArgumentException(s"Channel $chi is used in multiple clauses")
        j += 1
      allRendezvous = allRendezvous && (chi == null || chi.isRendezvous)
      i += 1
    allRendezvous

  private def getAnyChannelInError(clauses: Seq[SelectClause[?]]): ChannelError | Null =
    for clause <- clauses do
      val ch = clause.getChannel
      if ch != null then
        ch.closedForSend() match
          case ce: ChannelError => return ce
          case _                =>
    null
end Select

private[jox] final class SelectInstance(clausesCount: Int):
  private val state: AtomicReference[AnyRef] = new AtomicReference(SelectState.REGISTERING)
  private val storedClauses = mutable.ArrayBuffer.empty[StoredSelectClause]
  private var resultSelectedDuringRegistration: AnyRef = _

  def register[U](clause: SelectClause[U]): Boolean =
    val result = clause.register(this)
    result match
      case ss: StoredSelectClause =>
        storedClauses += ss
        true
      case cc: ChannelClosed =>
        state.set(cc)
        false
      case _ =>
        // clause was selected immediately
        resultSelectedDuringRegistration = result
        state.set(clause)
        false

  @throws[InterruptedException]
  def checkStateAndWait(allRendezvous: Boolean): AnyRef =
    while true do
      val currentState = state.get()
      currentState match
        case SelectState.REGISTERING =>
          val currentThread = Thread.currentThread()
          if state.compareAndSet(SelectState.REGISTERING, currentThread) then
            var spinIterations = if allRendezvous then Continuation.RENDEZVOUS_SPINS else 0
            while state.get() eq currentThread do
              if spinIterations > 0 then
                Thread.onSpinWait()
                spinIterations -= 1
              else
                LockSupport.park()
                if Thread.interrupted() then
                  if state.compareAndSet(currentThread, SelectState.INTERRUPTED) then
                    cleanup(null)
                    throw new InterruptedException()
                  else Thread.currentThread().interrupt()

        case clausesToReRegister: java.util.List[?] =>
          if state.compareAndSet(currentState, SelectState.REGISTERING) then
            val iter = clausesToReRegister.iterator()
            var done = false
            while iter.hasNext && !done do
              val clause = iter.next().asInstanceOf[SelectClause[?]]
              // cleanup the stored select for the clause we'll re-register
              val storedIter = storedClauses.iterator
              var found = false
              val newStored = mutable.ArrayBuffer.empty[StoredSelectClause]
              for stored <- storedClauses do
                if !found && (stored.clause eq clause) then
                  stored.cleanup()
                  found = true
                else newStored += stored
              storedClauses.clear()
              storedClauses ++= newStored

              if !register(clause) then done = true

        case selectedClause: SelectClause[?] @unchecked =>
          cleanup(selectedClause)
          return selectedClause.transformedRawValue(resultSelectedDuringRegistration).asInstanceOf[AnyRef]

        case ss: StoredSelectClause =>
          val selectedClause = ss.clause
          cleanup(selectedClause)
          return selectedClause.transformedRawValue(ss.payload.asInstanceOf[AnyRef]).asInstanceOf[AnyRef]

        case cc: ChannelClosed =>
          cleanup(null)
          return cc

        case _ =>
          throw new IllegalStateException(s"Unknown state: $currentState")
    end while
    throw new AssertionError("unreachable")

  private def cleanup(selected: SelectClause[?] | Null): Unit =
    for stored <- storedClauses do
      if !(stored.clause eq selected) then stored.cleanup()
    storedClauses.clear()

  /** Called by another thread to try selecting this clause. */
  def trySelect(storedSelectClause: StoredSelectClause): Boolean =
    while true do
      val currentState = state.get()
      currentState match
        case SelectState.REGISTERING =>
          val list = new java.util.ArrayList[SelectClause[?]](1)
          list.add(storedSelectClause.clause)
          if state.compareAndSet(currentState, list) then return false
        case clausesToReRegister: java.util.List[?] =>
          val newList = new java.util.ArrayList[SelectClause[?]](clausesToReRegister.size() + 1)
          newList.addAll(clausesToReRegister.asInstanceOf[java.util.List[SelectClause[?]]])
          newList.add(storedSelectClause.clause)
          if state.compareAndSet(currentState, newList) then return false
        case _: SelectClause[?] =>
          return false // already selected
        case _: StoredSelectClause =>
          return false // already selected
        case t: Thread =>
          if state.compareAndSet(currentState, storedSelectClause) then
            LockSupport.unpark(t)
            return true
        case SelectState.INTERRUPTED =>
          return false
        case _: ChannelClosed =>
          return false
        case _ =>
          throw new IllegalStateException(s"Unknown state: $currentState")
    false // unreachable

  /** Called when a channel is closed. */
  def channelClosed(channelClosed: ChannelClosed): Boolean =
    while true do
      val currentState = state.get()
      currentState match
        case SelectState.REGISTERING | (_: java.util.List[?]) =>
          if state.compareAndSet(currentState, channelClosed) then return true
        case _: SelectClause[?] | _: StoredSelectClause =>
          return false // already selected
        case t: Thread =>
          if state.compareAndSet(currentState, channelClosed) then
            LockSupport.unpark(t)
            return true
        case SelectState.INTERRUPTED =>
          return false
        case _: ChannelClosed =>
          return false
        case _ =>
          throw new IllegalStateException(s"Unknown state: $currentState")
    false // unreachable
end SelectInstance
