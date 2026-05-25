package ox.channels.jox

// Ported from: https://github.com/softwaremill/jox/blob/v1.1.2-channels/channels/src/main/java/com/softwaremill/jox/Select.java
// (inner class StoredSelectClause, lines 597-643)

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
