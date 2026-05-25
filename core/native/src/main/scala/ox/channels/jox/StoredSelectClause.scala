package ox.channels.jox

/** Keeps information about a select instance stored in a channel cell, awaiting completion. */
private[jox] final class StoredSelectClause(
    val select: SelectInstance,
    val segment: Segment,
    val cellIndex: Int,
    val isSender: Boolean,
    val clause: SelectClause[?],
    @volatile var payload: AnyRef | Null
):
  def cleanup(): Unit =
    clause.getChannel.nn.cleanupStoredSelectClause(segment, cellIndex, isSender)
end StoredSelectClause
