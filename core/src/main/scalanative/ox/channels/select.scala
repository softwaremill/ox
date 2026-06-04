package ox.channels

import ox.channels.jox.Select as JSelect

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

def select[T](clauses: Seq[SelectClause[T]]): SelectResult[T] = selectOrClosed(clauses).orThrow

//

def selectOrClosed[T1, T2](source1: Source[T1], source2: Source[T2]): T1 | T2 | ChannelClosed =
  selectOrClosed(source1.receiveClause, source2.receiveClause).map {
    case source1.Received(v) => v
    case source2.Received(v) => v
  }

def selectOrClosed[T1, T2, T3](source1: Source[T1], source2: Source[T2], source3: Source[T3]): T1 | T2 | T3 | ChannelClosed =
  selectOrClosed(source1.receiveClause, source2.receiveClause, source3.receiveClause).map {
    case source1.Received(v) => v
    case source2.Received(v) => v
    case source3.Received(v) => v
  }

def selectOrClosed[T1, T2, T3, T4](source1: Source[T1], source2: Source[T2], source3: Source[T3], source4: Source[T4]): T1 | T2 | T3 | T4 |
  ChannelClosed =
  selectOrClosed(source1.receiveClause, source2.receiveClause, source3.receiveClause, source4.receiveClause).map {
    case source1.Received(v) => v
    case source2.Received(v) => v
    case source3.Received(v) => v
    case source4.Received(v) => v
  }

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

def selectOrClosed[T](sources: Seq[Source[T]])(using DummyImplicit): T | ChannelClosed =
  selectOrClosed(sources.map(_.receiveClause: SelectClause[T])) match
    case r: Source[T]#Received => r.value
    case c: ChannelClosed      => c
    case _: Sink[?]#Sent       => throw new IllegalStateException()
    case _: DefaultResult[?]   => throw new IllegalStateException()

//

def select[T1, T2](source1: Source[T1], source2: Source[T2]): T1 | T2 =
  select(source1.receiveClause, source2.receiveClause) match
    case source1.Received(v) => v
    case source2.Received(v) => v

def select[T1, T2, T3](source1: Source[T1], source2: Source[T2], source3: Source[T3]): T1 | T2 | T3 =
  select(source1.receiveClause, source2.receiveClause, source3.receiveClause) match
    case source1.Received(v) => v
    case source2.Received(v) => v
    case source3.Received(v) => v

def select[T1, T2, T3, T4](source1: Source[T1], source2: Source[T2], source3: Source[T3], source4: Source[T4]): T1 | T2 | T3 | T4 =
  select(source1.receiveClause, source2.receiveClause, source3.receiveClause, source4.receiveClause) match
    case source1.Received(v) => v
    case source2.Received(v) => v
    case source3.Received(v) => v
    case source4.Received(v) => v

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

def select[T](sources: Seq[Source[T]])(using DummyImplicit): T | ChannelClosed =
  selectOrClosed(sources).orThrow

//

def selectOrClosedWithin[TV](
    timeout: FiniteDuration,
    timeoutValue: TV
)(clause1: SelectClause[?]): TV | clause1.Result | ChannelClosed =
  selectOrClosedWithin(timeout, timeoutValue)(List(clause1))
    .asInstanceOf[TV | clause1.Result | ChannelClosed]

def selectOrClosedWithin[TV](
    timeout: FiniteDuration,
    timeoutValue: TV
)(clause1: SelectClause[?], clause2: SelectClause[?]): TV | clause1.Result | clause2.Result | ChannelClosed =
  selectOrClosedWithin(timeout, timeoutValue)(List(clause1, clause2))
    .asInstanceOf[TV | clause1.Result | clause2.Result | ChannelClosed]

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

def selectOrClosedWithin[TV, T1](
    timeout: FiniteDuration,
    timeoutValue: TV
)(source1: Source[T1]): TV | T1 | ChannelClosed =
  selectOrClosedWithin(timeout, timeoutValue)(source1.receiveClause) match
    case source1.Received(v) => v
    case c: ChannelClosed    => c
    case tv                  => timeoutValue

def selectOrClosedWithin[TV, T1, T2](
    timeout: FiniteDuration,
    timeoutValue: TV
)(source1: Source[T1], source2: Source[T2]): TV | T1 | T2 | ChannelClosed =
  selectOrClosedWithin(timeout, timeoutValue)(source1.receiveClause, source2.receiveClause) match
    case source1.Received(v) => v
    case source2.Received(v) => v
    case c: ChannelClosed    => c
    case tv                  => timeoutValue

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

private object TimeoutMarker

def selectWithin(
    timeout: FiniteDuration
)(clause1: SelectClause[?]): clause1.Result =
  selectWithin(timeout)(List(clause1)).asInstanceOf[clause1.Result]

def selectWithin(
    timeout: FiniteDuration
)(clause1: SelectClause[?], clause2: SelectClause[?]): clause1.Result | clause2.Result =
  selectWithin(timeout)(List(clause1, clause2)).asInstanceOf[clause1.Result | clause2.Result]

def selectWithin(
    timeout: FiniteDuration
)(
    clause1: SelectClause[?],
    clause2: SelectClause[?],
    clause3: SelectClause[?]
): clause1.Result | clause2.Result | clause3.Result =
  selectWithin(timeout)(List(clause1, clause2, clause3))
    .asInstanceOf[clause1.Result | clause2.Result | clause3.Result]

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

def selectWithin[T](
    timeout: FiniteDuration
)(clauses: Seq[SelectClause[T]]): SelectResult[T] =
  val result = selectOrClosedWithin(timeout, TimeoutMarker)(clauses)
  if result == TimeoutMarker then throw new TimeoutException(s"select timed out after $timeout")
  else result.asInstanceOf[SelectResult[T] | ChannelClosed].orThrow

//

def selectWithin[T1](
    timeout: FiniteDuration
)(source1: Source[T1]): T1 =
  selectWithin(timeout)(source1.receiveClause) match
    case source1.Received(v) => v

def selectWithin[T1, T2](
    timeout: FiniteDuration
)(source1: Source[T1], source2: Source[T2]): T1 | T2 =
  selectWithin(timeout)(source1.receiveClause, source2.receiveClause) match
    case source1.Received(v) => v
    case source2.Received(v) => v

def selectWithin[T1, T2, T3](
    timeout: FiniteDuration
)(source1: Source[T1], source2: Source[T2], source3: Source[T3]): T1 | T2 | T3 =
  selectWithin(timeout)(source1.receiveClause, source2.receiveClause, source3.receiveClause) match
    case source1.Received(v) => v
    case source2.Received(v) => v
    case source3.Received(v) => v

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

def selectWithin[T](
    timeout: FiniteDuration
)(sources: Seq[Source[T]])(using DummyImplicit): T =
  val result = selectOrClosedWithin(timeout, TimeoutMarker)(sources)
  if result == TimeoutMarker then throw new TimeoutException(s"select timed out after $timeout")
  else result.asInstanceOf[T | ChannelClosed].orThrow
