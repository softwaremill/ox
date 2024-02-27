package ox.channels

import com.softwaremill.jox.Select as JSelect

import ox.channels.ChannelClosedUnion.{map, orThrow}

/** @see [[selectSafe(List[SelectClause])]]. */
def selectSafe(clause1: SelectClause[_], clause2: SelectClause[_]): clause1.Result | clause2.Result | ChannelClosed =
  selectSafe(List(clause1, clause2)).asInstanceOf[clause1.Result | clause2.Result | ChannelClosed]

/** @see [[selectSafe(List[SelectClause])]]. */
def selectSafe(
    clause1: SelectClause[_],
    clause2: SelectClause[_],
    clause3: SelectClause[_]
): clause1.Result | clause2.Result | clause3.Result | ChannelClosed =
  selectSafe(List(clause1, clause2, clause3)).asInstanceOf[clause1.Result | clause2.Result | clause3.Result | ChannelClosed]

/** @see [[selectSafe(List[SelectClause])]]. */
def selectSafe(
    clause1: SelectClause[_],
    clause2: SelectClause[_],
    clause3: SelectClause[_],
    clause4: SelectClause[_]
): clause1.Result | clause2.Result | clause3.Result | clause4.Result | ChannelClosed =
  selectSafe(List(clause1, clause2, clause3, clause4))
    .asInstanceOf[clause1.Result | clause2.Result | clause3.Result | clause4.Result | ChannelClosed]

/** @see [[selectSafe(List[SelectClause])]]. */
def selectSafe(
    clause1: SelectClause[_],
    clause2: SelectClause[_],
    clause3: SelectClause[_],
    clause4: SelectClause[_],
    clause5: SelectClause[_]
): clause1.Result | clause2.Result | clause3.Result | clause4.Result | clause5.Result | ChannelClosed =
  selectSafe(List(clause1, clause2, clause3, clause4, clause5))
    .asInstanceOf[clause1.Result | clause2.Result | clause3.Result | clause4.Result | clause5.Result | ChannelClosed]

/** Select exactly one clause to complete. Each clause should be created for a different channel. Clauses can be created using
  * [[Source.receiveClause]], [[Sink.sendClause]] and [[Default]].
  *
  * If a couple of the clauses can be completed immediately, the select is biased towards the clauses that appear first.
  *
  * If no clauses are given, returns [[ChannelClosed.Done]].
  *
  * For a variant which throws exceptions when any of the channels is closed, use [[select]].
  *
  * @param clauses
  *   The clauses, from which one will be selected.
  * @return
  *   The result returned by the selected clause, wrapped with [[SelectResult]], or a [[ChannelClosed]], when any of the channels is closed
  *   (done or in error).
  */
def selectSafe[T](clauses: List[SelectClause[T]]): SelectResult[T] | ChannelClosed =
  ChannelClosed.fromJoxOrT(JSelect.selectSafe(clauses.map(_.delegate): _*))

//

/** @see [[select(List[SelectClause])]]. */
def select(clause1: SelectClause[_], clause2: SelectClause[_]): clause1.Result | clause2.Result =
  select(List(clause1, clause2)).asInstanceOf[clause1.Result | clause2.Result]

/** @see [[select(List[SelectClause])]]. */
def select(
    clause1: SelectClause[_],
    clause2: SelectClause[_],
    clause3: SelectClause[_]
): clause1.Result | clause2.Result | clause3.Result =
  select(List(clause1, clause2, clause3)).asInstanceOf[clause1.Result | clause2.Result | clause3.Result]

/** @see [[select(List[SelectClause])]]. */
def select(
    clause1: SelectClause[_],
    clause2: SelectClause[_],
    clause3: SelectClause[_],
    clause4: SelectClause[_]
): clause1.Result | clause2.Result | clause3.Result | clause4.Result =
  select(List(clause1, clause2, clause3, clause4)).asInstanceOf[clause1.Result | clause2.Result | clause3.Result | clause4.Result]

/** @see [[select(List[SelectClause])]]. */
def select(
    clause1: SelectClause[_],
    clause2: SelectClause[_],
    clause3: SelectClause[_],
    clause4: SelectClause[_],
    clause5: SelectClause[_]
): clause1.Result | clause2.Result | clause3.Result | clause4.Result | clause5.Result =
  select(List(clause1, clause2, clause3, clause4, clause5))
    .asInstanceOf[clause1.Result | clause2.Result | clause3.Result | clause4.Result | clause5.Result]

/** Select exactly one clause to complete. Each clause should be created for a different channel. Clauses can be created using
  * [[Source.receiveClause]], [[Sink.sendClause]] and [[Default]].
  *
  * If a couple of the clauses can be completed immediately, the select is biased towards the clauses that appear first.
  *
  * If no clauses are given, returns [[ChannelClosed.Done]].
  *
  * For a variant which doesn't throw exceptions when any of the channels is closed, use [[selectSafe]].
  *
  * @param clauses
  *   The clauses, from which one will be selected.
  * @return
  *   The result returned by the selected clause, wrapped with [[SelectResult]].
  * @throws ChannelClosedException
  *   When any of the channels is closed (done or in error).
  */
def select[T](clauses: List[SelectClause[T]]): SelectResult[T] = selectSafe(clauses).orThrow

//

/** @see [[selectSafe(List[Source])]]. */
def selectSafe[T1, T2](source1: Source[T1], source2: Source[T2]): T1 | T2 | ChannelClosed =
  selectSafe(source1.receiveClause, source2.receiveClause).map {
    case source1.Received(v) => v
    case source2.Received(v) => v
  }

/** @see [[selectSafe(List[Source])]]. */
def selectSafe[T1, T2, T3](source1: Source[T1], source2: Source[T2], source3: Source[T3]): T1 | T2 | T3 | ChannelClosed =
  selectSafe(source1.receiveClause, source2.receiveClause, source3.receiveClause).map {
    case source1.Received(v) => v
    case source2.Received(v) => v
    case source3.Received(v) => v
  }

/** @see [[selectSafe(List[Source])]]. */
def selectSafe[T1, T2, T3, T4](source1: Source[T1], source2: Source[T2], source3: Source[T3], source4: Source[T4]): T1 | T2 | T3 | T4 |
  ChannelClosed =
  selectSafe(source1.receiveClause, source2.receiveClause, source3.receiveClause, source4.receiveClause).map {
    case source1.Received(v) => v
    case source2.Received(v) => v
    case source3.Received(v) => v
    case source4.Received(v) => v
  }

/** @see [[selectSafe(List[Source])]]. */
def selectSafe[T1, T2, T3, T4, T5](
    source1: Source[T1],
    source2: Source[T2],
    source3: Source[T3],
    source4: Source[T4],
    source5: Source[T5]
): T1 | T2 | T3 | T4 | T5 | ChannelClosed =
  selectSafe(source1.receiveClause, source2.receiveClause, source3.receiveClause, source4.receiveClause, source5.receiveClause).map {
    case source1.Received(v) => v
    case source2.Received(v) => v
    case source3.Received(v) => v
    case source4.Received(v) => v
    case source5.Received(v) => v
  }

/** Select exactly one source, from which to receive a value. Sources should not repeat. Clauses can be created using
  * [[Source.receiveClause]], [[Sink.sendClause]] and [[Default]].
  *
  * If a couple of the sources have values which can be received immediately, the select is biased towards the source that appears first.
  *
  * If no sources are given, returns [[ChannelClosed.Done]].
  *
  * For a variant which throws exceptions when any of the channels is closed, use [[select]].
  *
  * @param sources
  *   The sources, from which a value will be received.
  * @return
  *   The value received from the selected source, or a [[ChannelClosed]], when any of the channels is closed (done or in error).
  */
def selectSafe[T](sources: List[Source[T]])(using DummyImplicit): T | ChannelClosed =
  selectSafe(sources.map(_.receiveClause: SelectClause[T])) match
    case r: Source[T]#Received => r.value
    case c: ChannelClosed      => c
    case _: Sink[_]#Sent       => throw new IllegalStateException()
    case _: DefaultResult[_]   => throw new IllegalStateException()

//

/** @see [[select(List[Source])]]. */
def select[T1, T2](source1: Source[T1], source2: Source[T2]): T1 | T2 =
  select(source1.receiveClause, source2.receiveClause) match {
    case source1.Received(v) => v
    case source2.Received(v) => v
  }

/** @see [[select(List[Source])]]. */
def select[T1, T2, T3](source1: Source[T1], source2: Source[T2], source3: Source[T3]): T1 | T2 | T3 =
  select(source1.receiveClause, source2.receiveClause, source3.receiveClause) match {
    case source1.Received(v) => v
    case source2.Received(v) => v
    case source3.Received(v) => v
  }

/** @see [[select(List[Source])]]. */
def select[T1, T2, T3, T4](source1: Source[T1], source2: Source[T2], source3: Source[T3], source4: Source[T4]): T1 | T2 | T3 | T4 =
  select(source1.receiveClause, source2.receiveClause, source3.receiveClause, source4.receiveClause) match {
    case source1.Received(v) => v
    case source2.Received(v) => v
    case source3.Received(v) => v
    case source4.Received(v) => v
  }

/** @see [[select(List[Source])]]. */
def select[T1, T2, T3, T4, T5](
    source1: Source[T1],
    source2: Source[T2],
    source3: Source[T3],
    source4: Source[T4],
    source5: Source[T5]
): T1 | T2 | T3 | T4 | T5 =
  select(source1.receiveClause, source2.receiveClause, source3.receiveClause, source4.receiveClause, source5.receiveClause) match {
    case source1.Received(v) => v
    case source2.Received(v) => v
    case source3.Received(v) => v
    case source4.Received(v) => v
    case source5.Received(v) => v
  }

/** Select exactly one source, from which to receive a value. Sources should not repeat. Clauses can be created using
  * [[Source.receiveClause]], [[Sink.sendClause]] and [[Default]].
  *
  * If a couple of the sources have values which can be received immediately, the select is biased towards the source that appears first.
  *
  * If no sources are given, returns [[ChannelClosed.Done]].
  *
  * For a variant which doesn't throw exceptions when any of the channels is closed, use [[selectSafe]].
  *
  * @param sources
  *   The sources, from which a value will be received.
  * @return
  *   The value received from the selected source.
  * @throws ChannelClosedException
  *   When any of the channels is closed (done or in error).
  */
def select[T](sources: List[Source[T]])(using DummyImplicit): T | ChannelClosed =
  selectSafe(sources).orThrow
