package ox.channels

import com.softwaremill.jox.Select as JSelect

/** @see [[select(List[SelectClause])]]. */
def select(clause1: SelectClause[_], clause2: SelectClause[_]): clause1.Result | clause2.Result | ChannelClosed =
  select(List(clause1, clause2)).asInstanceOf[clause1.Result | clause2.Result | ChannelClosed]

/** @see [[select(List[SelectClause])]]. */
def select(
    clause1: SelectClause[_],
    clause2: SelectClause[_],
    clause3: SelectClause[_]
): clause1.Result | clause2.Result | clause3.Result | ChannelClosed =
  select(List(clause1, clause2, clause3)).asInstanceOf[clause1.Result | clause2.Result | clause3.Result | ChannelClosed]

//

/** @see [[select(List[Source])]]. */
def select[T1, T2](source1: Source[T1], source2: Source[T2]): T1 | T2 | ChannelClosed =
  select(source1.receiveClause, source2.receiveClause).map {
    case source1.Received(v) => v
    case source2.Received(v) => v
  }

/** @see [[select(List[Source])]]. */
def select[T1, T2, T3](source1: Source[T1], source2: Source[T2], source3: Source[T3]): T1 | T2 | T3 | ChannelClosed =
  select(source1.receiveClause, source2.receiveClause, source3.receiveClause).map {
    case source1.Received(v) => v
    case source2.Received(v) => v
    case source3.Received(v) => v
  }

/** Select exactly one clause to complete. Each clause should be created for a different channel.
  *
  * If a couple of the clauses can be completed immediately, the select is biased towards the clauses that appear first.
  *
  * If no clauses are given, or all clauses become filtered out, returns [[ChannelClosed.Done]].
  *
  * If a receive clause is selected for a channel that is done, select restarts, unless the clause is created with
  * [[Source.receiveOrDoneClause]].
  *
  * @param clauses
  *   The clauses, from which one will be selected.
  * @return
  *   The result returned by the selected clause, wrapped with [[SelectResult]], or a [[ChannelClosed]], when any of the channels is closed
  *   (done or in error), and the select doesn't restart.
  */
def select[T](clauses: List[SelectClause[T]]): SelectResult[T] | ChannelClosed =
  ChannelClosed.fromJoxOrT(JSelect.selectSafe(clauses.map(_.delegate): _*))

/** Same as [[select(List[SelectClause])]], but accepts [[Source]]s directly (without the need to create receive clauses), and wraps the
  * result.
  */
def select[T](sources: List[Source[T]])(using DummyImplicit): T | ChannelClosed =
  select(sources.map(_.receiveClause: SelectClause[T])) match
    case r: Source[T]#Received => r.value
    case c: ChannelClosed      => c
    case _: Sink[_]#Sent       => throw new IllegalStateException()
    case _: DefaultResult[_]   => throw new IllegalStateException()
