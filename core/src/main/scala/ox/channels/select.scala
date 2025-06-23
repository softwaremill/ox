package ox.channels

import com.softwaremill.jox.Select as JSelect

import ox.channels.ChannelClosedUnion.{map, orThrow}
import ox.{discard, forkUnsupervised, sleep, unsupervised}
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.TimeoutException

/** @see [[selectOrClosed(Seq[SelectClause])]]. */
def selectOrClosed(clause1: SelectClause[?], clause2: SelectClause[?]): clause1.Result | clause2.Result | ChannelClosed =
  selectOrClosed(List(clause1, clause2)).asInstanceOf[clause1.Result | clause2.Result | ChannelClosed]

/** @see [[selectOrClosed(Seq[SelectClause])]]. */
def selectOrClosed(
    clause1: SelectClause[?],
    clause2: SelectClause[?],
    clause3: SelectClause[?]
): clause1.Result | clause2.Result | clause3.Result | ChannelClosed =
  selectOrClosed(List(clause1, clause2, clause3)).asInstanceOf[clause1.Result | clause2.Result | clause3.Result | ChannelClosed]

/** @see [[selectOrClosed(Seq[SelectClause])]]. */
def selectOrClosed(
    clause1: SelectClause[?],
    clause2: SelectClause[?],
    clause3: SelectClause[?],
    clause4: SelectClause[?]
): clause1.Result | clause2.Result | clause3.Result | clause4.Result | ChannelClosed =
  selectOrClosed(List(clause1, clause2, clause3, clause4))
    .asInstanceOf[clause1.Result | clause2.Result | clause3.Result | clause4.Result | ChannelClosed]

/** @see [[selectOrClosed(Seq[SelectClause])]]. */
def selectOrClosed(
    clause1: SelectClause[?],
    clause2: SelectClause[?],
    clause3: SelectClause[?],
    clause4: SelectClause[?],
    clause5: SelectClause[?]
): clause1.Result | clause2.Result | clause3.Result | clause4.Result | clause5.Result | ChannelClosed =
  selectOrClosed(List(clause1, clause2, clause3, clause4, clause5))
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
def selectOrClosed[T](clauses: Seq[SelectClause[T]]): SelectResult[T] | ChannelClosed =
  ChannelClosed.fromJoxOrT(JSelect.selectOrClosed(clauses.map(_.delegate)*))

//

/** @see [[select(Seq[SelectClause])]]. */
def select(clause1: SelectClause[?], clause2: SelectClause[?]): clause1.Result | clause2.Result =
  select(List(clause1, clause2)).asInstanceOf[clause1.Result | clause2.Result]

/** @see [[select(Seq[SelectClause])]]. */
def select(
    clause1: SelectClause[?],
    clause2: SelectClause[?],
    clause3: SelectClause[?]
): clause1.Result | clause2.Result | clause3.Result =
  select(List(clause1, clause2, clause3)).asInstanceOf[clause1.Result | clause2.Result | clause3.Result]

/** @see [[select(Seq[SelectClause])]]. */
def select(
    clause1: SelectClause[?],
    clause2: SelectClause[?],
    clause3: SelectClause[?],
    clause4: SelectClause[?]
): clause1.Result | clause2.Result | clause3.Result | clause4.Result =
  select(List(clause1, clause2, clause3, clause4)).asInstanceOf[clause1.Result | clause2.Result | clause3.Result | clause4.Result]

/** @see [[select(Seq[SelectClause])]]. */
def select(
    clause1: SelectClause[?],
    clause2: SelectClause[?],
    clause3: SelectClause[?],
    clause4: SelectClause[?],
    clause5: SelectClause[?]
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
  * For a variant which doesn't throw exceptions when any of the channels is closed, use [[selectOrClosed]].
  *
  * @param clauses
  *   The clauses, from which one will be selected.
  * @return
  *   The result returned by the selected clause, wrapped with [[SelectResult]].
  * @throws ChannelClosedException
  *   When any of the channels is closed (done or in error).
  */
def select[T](clauses: Seq[SelectClause[T]]): SelectResult[T] = selectOrClosed(clauses).orThrow

//

/** @see [[selectOrClosed(Seq[Source])]]. */
def selectOrClosed[T1, T2](source1: Source[T1], source2: Source[T2]): T1 | T2 | ChannelClosed =
  selectOrClosed(source1.receiveClause, source2.receiveClause).map {
    case source1.Received(v) => v
    case source2.Received(v) => v
  }

/** @see [[selectOrClosed(Seq[Source])]]. */
def selectOrClosed[T1, T2, T3](source1: Source[T1], source2: Source[T2], source3: Source[T3]): T1 | T2 | T3 | ChannelClosed =
  selectOrClosed(source1.receiveClause, source2.receiveClause, source3.receiveClause).map {
    case source1.Received(v) => v
    case source2.Received(v) => v
    case source3.Received(v) => v
  }

/** @see [[selectOrClosed(Seq[Source])]]. */
def selectOrClosed[T1, T2, T3, T4](source1: Source[T1], source2: Source[T2], source3: Source[T3], source4: Source[T4]): T1 | T2 | T3 | T4 |
  ChannelClosed =
  selectOrClosed(source1.receiveClause, source2.receiveClause, source3.receiveClause, source4.receiveClause).map {
    case source1.Received(v) => v
    case source2.Received(v) => v
    case source3.Received(v) => v
    case source4.Received(v) => v
  }

/** @see [[selectOrClosed(Seq[Source])]]. */
def selectOrClosed[T1, T2, T3, T4, T5](
    source1: Source[T1],
    source2: Source[T2],
    source3: Source[T3],
    source4: Source[T4],
    source5: Source[T5]
): T1 | T2 | T3 | T4 | T5 | ChannelClosed =
  selectOrClosed(source1.receiveClause, source2.receiveClause, source3.receiveClause, source4.receiveClause, source5.receiveClause).map {
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
def selectOrClosed[T](sources: Seq[Source[T]])(using DummyImplicit): T | ChannelClosed =
  selectOrClosed(sources.map(_.receiveClause: SelectClause[T])) match
    case r: Source[T]#Received => r.value
    case c: ChannelClosed      => c
    case _: Sink[?]#Sent       => throw new IllegalStateException()
    case _: DefaultResult[?]   => throw new IllegalStateException()

//

/** @see [[select(Seq[Source])]]. */
def select[T1, T2](source1: Source[T1], source2: Source[T2]): T1 | T2 =
  select(source1.receiveClause, source2.receiveClause) match
    case source1.Received(v) => v
    case source2.Received(v) => v

/** @see [[select(Seq[Source])]]. */
def select[T1, T2, T3](source1: Source[T1], source2: Source[T2], source3: Source[T3]): T1 | T2 | T3 =
  select(source1.receiveClause, source2.receiveClause, source3.receiveClause) match
    case source1.Received(v) => v
    case source2.Received(v) => v
    case source3.Received(v) => v

/** @see [[select(Seq[Source])]]. */
def select[T1, T2, T3, T4](source1: Source[T1], source2: Source[T2], source3: Source[T3], source4: Source[T4]): T1 | T2 | T3 | T4 =
  select(source1.receiveClause, source2.receiveClause, source3.receiveClause, source4.receiveClause) match
    case source1.Received(v) => v
    case source2.Received(v) => v
    case source3.Received(v) => v
    case source4.Received(v) => v

/** @see [[select(Seq[Source])]]. */
def select[T1, T2, T3, T4, T5](
    source1: Source[T1],
    source2: Source[T2],
    source3: Source[T3],
    source4: Source[T4],
    source5: Source[T5]
): T1 | T2 | T3 | T4 | T5 =
  select(source1.receiveClause, source2.receiveClause, source3.receiveClause, source4.receiveClause, source5.receiveClause) match
    case source1.Received(v) => v
    case source2.Received(v) => v
    case source3.Received(v) => v
    case source4.Received(v) => v
    case source5.Received(v) => v

/** Select exactly one source, from which to receive a value. Sources should not repeat. Clauses can be created using
  * [[Source.receiveClause]], [[Sink.sendClause]] and [[Default]].
  *
  * If a couple of the sources have values which can be received immediately, the select is biased towards the source that appears first.
  *
  * If no sources are given, returns [[ChannelClosed.Done]].
  *
  * For a variant which doesn't throw exceptions when any of the channels is closed, use [[selectOrClosed]].
  *
  * @param sources
  *   The sources, from which a value will be received.
  * @return
  *   The value received from the selected source.
  * @throws ChannelClosedException
  *   When any of the channels is closed (done or in error).
  */
def select[T](sources: Seq[Source[T]])(using DummyImplicit): T | ChannelClosed =
  selectOrClosed(sources).orThrow

//

/** @see [[selectOrClosedWithin(FiniteDuration, TimeoutValue)(Seq[SelectClause])]]. */
def selectOrClosedWithin[TV](
    timeout: FiniteDuration,
    timeoutValue: TV
)(clause1: SelectClause[?]): TV | clause1.Result | ChannelClosed =
  selectOrClosedWithin(timeout, timeoutValue)(List(clause1))
    .asInstanceOf[TV | clause1.Result | ChannelClosed]

/** @see [[selectOrClosedWithin(FiniteDuration, TimeoutValue)(Seq[SelectClause])]]. */
def selectOrClosedWithin[TV](
    timeout: FiniteDuration,
    timeoutValue: TV
)(clause1: SelectClause[?], clause2: SelectClause[?]): TV | clause1.Result | clause2.Result | ChannelClosed =
  selectOrClosedWithin(timeout, timeoutValue)(List(clause1, clause2))
    .asInstanceOf[TV | clause1.Result | clause2.Result | ChannelClosed]

/** @see [[selectOrClosedWithin(FiniteDuration, TimeoutValue)(Seq[SelectClause])]]. */
def selectOrClosedWithin[TV](
    timeout: FiniteDuration,
    timeoutValue: TV
)(
    clause1: SelectClause[?],
    clause2: SelectClause[?],
    clause3: SelectClause[?]
): TV | clause1.Result | clause2.Result | clause3.Result | ChannelClosed =
  selectOrClosedWithin(timeout, timeoutValue)(List(clause1, clause2, clause3))
    .asInstanceOf[TV | clause1.Result | clause2.Result | clause3.Result | ChannelClosed]

/** @see [[selectOrClosedWithin(FiniteDuration, TimeoutValue)(Seq[SelectClause])]]. */
def selectOrClosedWithin[TV](
    timeout: FiniteDuration,
    timeoutValue: TV
)(
    clause1: SelectClause[?],
    clause2: SelectClause[?],
    clause3: SelectClause[?],
    clause4: SelectClause[?]
): TV | clause1.Result | clause2.Result | clause3.Result | clause4.Result | ChannelClosed =
  selectOrClosedWithin(timeout, timeoutValue)(List(clause1, clause2, clause3, clause4))
    .asInstanceOf[TV | clause1.Result | clause2.Result | clause3.Result | clause4.Result | ChannelClosed]

/** @see [[selectOrClosedWithin(FiniteDuration, TimeoutValue)(Seq[SelectClause])]]. */
def selectOrClosedWithin[TV](
    timeout: FiniteDuration,
    timeoutValue: TV
)(
    clause1: SelectClause[?],
    clause2: SelectClause[?],
    clause3: SelectClause[?],
    clause4: SelectClause[?],
    clause5: SelectClause[?]
): TV | clause1.Result | clause2.Result | clause3.Result | clause4.Result | clause5.Result | ChannelClosed =
  selectOrClosedWithin(timeout, timeoutValue)(List(clause1, clause2, clause3, clause4, clause5)).asInstanceOf[
    TV | clause1.Result | clause2.Result | clause3.Result | clause4.Result | clause5.Result | ChannelClosed
  ]

/** Select exactly one clause to complete within the given timeout. Each clause should be created for a different channel. Clauses can be
  * created using [[Source.receiveClause]], [[Sink.sendClause]] and [[Default]].
  *
  * If any clause can be completed immediately, it is selected (no timeout is needed). If a couple of the clauses can be completed
  * immediately, the select is biased towards the clauses that appear first.
  *
  * If no clauses are given, returns the timeout value immediately.
  *
  * The implementation creates a buffered channel of size 1, an [[unsupervised]] scope, and within that scope starts a fork that sends the
  * timeout value to the channel after the specified timeout duration. The select then chooses from the provided clauses or the timeout
  * channel.
  *
  * @param timeout
  *   The maximum time to wait for any clause to complete
  * @param timeoutValue
  *   The value to return if the timeout is reached before any clause completes
  * @param clauses
  *   The clauses, from which one will be selected (or timeout will occur)
  * @return
  *   Either the timeout value, the result returned by the selected clause, or a [[ChannelClosed]] when any of the channels is closed (done
  *   or in error).
  */
def selectOrClosedWithin[TV, T](
    timeout: FiniteDuration,
    timeoutValue: TV
)(clauses: Seq[SelectClause[T]]): TV | SelectResult[T] | ChannelClosed =
  if clauses.isEmpty then timeoutValue
  else
    unsupervised {
      val timeoutChannel = Channel.withCapacity[Unit](1)

      forkUnsupervised {
        sleep(timeout)
        timeoutChannel.sendOrClosed(()).discard
      }.discard

      val clausesWithTimeout = clauses :+ timeoutChannel.receiveClause

      selectOrClosed(clausesWithTimeout) match
        case timeoutChannel.Received(_)    => timeoutValue
        case c: ChannelClosed              => c
        case r: SelectResult[?] @unchecked => r.asInstanceOf[SelectResult[T]]
      end match
    }

//

/** @see [[selectOrClosedWithin(FiniteDuration, TimeoutValue)(Seq[Source])]]. */
def selectOrClosedWithin[TV, T1](
    timeout: FiniteDuration,
    timeoutValue: TV
)(source1: Source[T1]): TV | T1 | ChannelClosed =
  selectOrClosedWithin(timeout, timeoutValue)(source1.receiveClause) match
    case source1.Received(v) => v
    case c: ChannelClosed    => c
    case tv                  => timeoutValue

/** @see [[selectOrClosedWithin(FiniteDuration, TimeoutValue)(Seq[Source])]]. */
def selectOrClosedWithin[TV, T1, T2](
    timeout: FiniteDuration,
    timeoutValue: TV
)(source1: Source[T1], source2: Source[T2]): TV | T1 | T2 | ChannelClosed =
  selectOrClosedWithin(timeout, timeoutValue)(source1.receiveClause, source2.receiveClause) match
    case source1.Received(v) => v
    case source2.Received(v) => v
    case c: ChannelClosed    => c
    case tv                  => timeoutValue

/** @see [[selectOrClosedWithin(FiniteDuration, TimeoutValue)(Seq[Source])]]. */
def selectOrClosedWithin[TimeoutValue, T1, T2, T3](
    timeout: FiniteDuration,
    timeoutValue: TimeoutValue
)(source1: Source[T1], source2: Source[T2], source3: Source[T3]): TimeoutValue | T1 | T2 | T3 | ChannelClosed =
  selectOrClosedWithin(timeout, timeoutValue)(source1.receiveClause, source2.receiveClause, source3.receiveClause) match
    case source1.Received(v) => v
    case source2.Received(v) => v
    case source3.Received(v) => v
    case c: ChannelClosed    => c
    case tv                  => timeoutValue

/** @see [[selectOrClosedWithin(FiniteDuration, TimeoutValue)(Seq[Source])]]. */
def selectOrClosedWithin[TV, T1, T2, T3, T4](
    timeout: FiniteDuration,
    timeoutValue: TV
)(source1: Source[T1], source2: Source[T2], source3: Source[T3], source4: Source[T4]): TV | T1 | T2 | T3 | T4 | ChannelClosed =
  selectOrClosedWithin(timeout, timeoutValue)(
    source1.receiveClause,
    source2.receiveClause,
    source3.receiveClause,
    source4.receiveClause
  ) match
    case source1.Received(v) => v
    case source2.Received(v) => v
    case source3.Received(v) => v
    case source4.Received(v) => v
    case c: ChannelClosed    => c
    case tv                  => timeoutValue

/** @see [[selectOrClosedWithin(FiniteDuration, TimeoutValue)(Seq[Source])]]. */
def selectOrClosedWithin[TV, T1, T2, T3, T4, T5](
    timeout: FiniteDuration,
    timeoutValue: TV
)(
    source1: Source[T1],
    source2: Source[T2],
    source3: Source[T3],
    source4: Source[T4],
    source5: Source[T5]
): TV | T1 | T2 | T3 | T4 | T5 | ChannelClosed =
  selectOrClosedWithin(timeout, timeoutValue)(
    source1.receiveClause,
    source2.receiveClause,
    source3.receiveClause,
    source4.receiveClause,
    source5.receiveClause
  ) match
    case source1.Received(v) => v
    case source2.Received(v) => v
    case source3.Received(v) => v
    case source4.Received(v) => v
    case source5.Received(v) => v
    case c: ChannelClosed    => c
    case tv                  => timeoutValue

/** Select exactly one source from which to receive a value, within the given timeout. Sources should not repeat.
  *
  * If any source has a value that can be received immediately, it is selected (no timeout is needed). If a couple of the sources have
  * values that can be received immediately, the select is biased towards the source that appears first.
  *
  * If no sources are given, returns the timeout value immediately.
  *
  * The implementation creates a buffered channel of size 1, an unsupervised scope, and within that scope starts a fork that sends the
  * timeout value to the channel after the specified timeout duration. The select then chooses from the provided sources or the timeout
  * channel.
  *
  * @param timeout
  *   The maximum time to wait for any source to have a value available
  * @param timeoutValue
  *   The value to return if the timeout is reached before any source has a value available
  * @param sources
  *   The sources, from which a value will be received (or timeout will occur)
  * @return
  *   Either the timeout value, the value received from the selected source, or a [[ChannelClosed]] when any of the channels is closed (done
  *   or in error).
  */
def selectOrClosedWithin[TV, T](
    timeout: FiniteDuration,
    timeoutValue: TV
)(sources: Seq[Source[T]])(using DummyImplicit): TV | T | ChannelClosed =
  selectOrClosedWithin(timeout, timeoutValue)(sources.map(_.receiveClause: SelectClause[T])) match
    case r: Source[T]#Received => r.value
    case c: ChannelClosed      => c
    case _: Sink[?]#Sent       => throw new IllegalStateException()
    case _: DefaultResult[?]   => throw new IllegalStateException()
    case _: TV @unchecked      => timeoutValue

//

/** A unique private marker object used to detect timeout in selectWithin variants */
private object TimeoutMarker

/** @see [[selectWithin(Seq[SelectClause])]]. */
def selectWithin(
    timeout: FiniteDuration
)(clause1: SelectClause[?]): clause1.Result =
  selectWithin(timeout)(List(clause1)).asInstanceOf[clause1.Result]

/** @see [[selectWithin(Seq[SelectClause])]]. */
def selectWithin(
    timeout: FiniteDuration
)(clause1: SelectClause[?], clause2: SelectClause[?]): clause1.Result | clause2.Result =
  selectWithin(timeout)(List(clause1, clause2)).asInstanceOf[clause1.Result | clause2.Result]

/** @see [[selectWithin(Seq[SelectClause])]]. */
def selectWithin(
    timeout: FiniteDuration
)(
    clause1: SelectClause[?],
    clause2: SelectClause[?],
    clause3: SelectClause[?]
): clause1.Result | clause2.Result | clause3.Result =
  selectWithin(timeout)(List(clause1, clause2, clause3))
    .asInstanceOf[clause1.Result | clause2.Result | clause3.Result]

/** @see [[selectWithin(Seq[SelectClause])]]. */
def selectWithin(
    timeout: FiniteDuration
)(
    clause1: SelectClause[?],
    clause2: SelectClause[?],
    clause3: SelectClause[?],
    clause4: SelectClause[?]
): clause1.Result | clause2.Result | clause3.Result | clause4.Result =
  selectWithin(timeout)(List(clause1, clause2, clause3, clause4))
    .asInstanceOf[clause1.Result | clause2.Result | clause3.Result | clause4.Result]

/** @see [[selectWithin(Seq[SelectClause])]]. */
def selectWithin(
    timeout: FiniteDuration
)(
    clause1: SelectClause[?],
    clause2: SelectClause[?],
    clause3: SelectClause[?],
    clause4: SelectClause[?],
    clause5: SelectClause[?]
): clause1.Result | clause2.Result | clause3.Result | clause4.Result | clause5.Result =
  selectWithin(timeout)(List(clause1, clause2, clause3, clause4, clause5))
    .asInstanceOf[clause1.Result | clause2.Result | clause3.Result | clause4.Result | clause5.Result]

/** Select exactly one clause to complete within the given timeout. Each clause should be created for a different channel. Clauses can be
  * created using [[Source.receiveClause]], [[Sink.sendClause]] and [[Default]].
  *
  * If any clause can be completed immediately, it is selected (no timeout is needed). If a couple of the clauses can be completed
  * immediately, the select is biased towards the clauses that appear first.
  *
  * If no clauses are given, throws a [[TimeoutException]] immediately.
  *
  * The implementation delegates to [[selectOrClosedWithin]] using a unique private timeout marker value.
  *
  * @param timeout
  *   The maximum time to wait for any clause to complete
  * @param clauses
  *   The clauses, from which one will be selected (or timeout will occur)
  * @return
  *   The result returned by the selected clause, wrapped with [[SelectResult]].
  * @throws TimeoutException
  *   When the timeout is reached before any clause completes.
  * @throws ChannelClosedException
  *   When any of the channels is closed (done or in error).
  */
def selectWithin[T](
    timeout: FiniteDuration
)(clauses: Seq[SelectClause[T]]): SelectResult[T] =
  val result = selectOrClosedWithin(timeout, TimeoutMarker)(clauses)
  if result == TimeoutMarker then throw new TimeoutException(s"select timed out after $timeout")
  else result.asInstanceOf[SelectResult[T] | ChannelClosed].orThrow

//

/** @see [[selectWithin(Seq[Source])]]. */
def selectWithin[T1](
    timeout: FiniteDuration
)(source1: Source[T1]): T1 =
  selectWithin(timeout)(source1.receiveClause) match
    case source1.Received(v) => v

/** @see [[selectWithin(Seq[Source])]]. */
def selectWithin[T1, T2](
    timeout: FiniteDuration
)(source1: Source[T1], source2: Source[T2]): T1 | T2 =
  selectWithin(timeout)(source1.receiveClause, source2.receiveClause) match
    case source1.Received(v) => v
    case source2.Received(v) => v

/** @see [[selectWithin(Seq[Source])]]. */
def selectWithin[T1, T2, T3](
    timeout: FiniteDuration
)(source1: Source[T1], source2: Source[T2], source3: Source[T3]): T1 | T2 | T3 =
  selectWithin(timeout)(source1.receiveClause, source2.receiveClause, source3.receiveClause) match
    case source1.Received(v) => v
    case source2.Received(v) => v
    case source3.Received(v) => v

/** @see [[selectWithin(Seq[Source])]]. */
def selectWithin[T1, T2, T3, T4](
    timeout: FiniteDuration
)(source1: Source[T1], source2: Source[T2], source3: Source[T3], source4: Source[T4]): T1 | T2 | T3 | T4 =
  selectWithin(timeout)(
    source1.receiveClause,
    source2.receiveClause,
    source3.receiveClause,
    source4.receiveClause
  ) match
    case source1.Received(v) => v
    case source2.Received(v) => v
    case source3.Received(v) => v
    case source4.Received(v) => v

/** @see [[selectWithin(Seq[Source])]]. */
def selectWithin[T1, T2, T3, T4, T5](
    timeout: FiniteDuration
)(
    source1: Source[T1],
    source2: Source[T2],
    source3: Source[T3],
    source4: Source[T4],
    source5: Source[T5]
): T1 | T2 | T3 | T4 | T5 =
  selectWithin(timeout)(
    source1.receiveClause,
    source2.receiveClause,
    source3.receiveClause,
    source4.receiveClause,
    source5.receiveClause
  ) match
    case source1.Received(v) => v
    case source2.Received(v) => v
    case source3.Received(v) => v
    case source4.Received(v) => v
    case source5.Received(v) => v

/** Select exactly one source from which to receive a value, within the given timeout. Sources should not repeat.
  *
  * If any source has a value that can be received immediately, it is selected (no timeout is needed). If a couple of the sources have
  * values that can be received immediately, the select is biased towards the source that appears first.
  *
  * If no sources are given, throws a [[TimeoutException]] immediately.
  *
  * The implementation delegates to [[selectOrClosedWithin]] using a unique private timeout marker value.
  *
  * @param timeout
  *   The maximum time to wait for any source to have a value available
  * @param sources
  *   The sources, from which a value will be received (or timeout will occur)
  * @return
  *   The value received from the selected source.
  * @throws TimeoutException
  *   When the timeout is reached before any source has a value available.
  * @throws ChannelClosedException
  *   When any of the channels is closed (done or in error).
  */
def selectWithin[T](
    timeout: FiniteDuration
)(sources: Seq[Source[T]])(using DummyImplicit): T =
  val result = selectOrClosedWithin(timeout, TimeoutMarker)(sources)
  if result == TimeoutMarker then throw new TimeoutException(s"select timed out after $timeout")
  else result.asInstanceOf[T | ChannelClosed].orThrow
