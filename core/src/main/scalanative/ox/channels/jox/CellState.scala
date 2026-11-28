package ox.channels.jox

// Ported from: https://github.com/softwaremill/jox/blob/v1.1.2-channels/channels/src/main/java/com/softwaremill/jox/Channel.java
// (inner enums/classes: CellState, SendResult, ReceiveResult, ExpandBufferResult, ContinuationMarker, ChannelClosedMarker, SentClauseMarker;
//  plus RestartSelectMarker, SelectState, TimeoutMarker from Select.java)

// Possible states of a cell: one of these enum constants, Continuation, StoredSelectClause, or a buffered value
private[ox] enum CellState:
  case DONE
  case INTERRUPTED_SEND // the send/receive differentiation is important for expandBuffer
  case INTERRUPTED_RECEIVE
  case BROKEN
  case IN_BUFFER // used to inform a potentially concurrent sender that the cell is now in the buffer
  case RESUMING // expandBuffer is resuming a sender
  case CLOSED
end CellState

private[ox] enum SendResult:
  case AWAITED, BUFFERED, RESUMED, FAILED, CLOSED

private[ox] enum ReceiveResult:
  case FAILED, CLOSED

private[ox] enum ExpandBufferResult:
  case DONE, FAILED, CLOSED

private[ox] enum ContinuationMarker:
  case INTERRUPTED

private[ox] enum ChannelClosedMarker:
  case CLOSED

private[ox] enum SentClauseMarker:
  case SENT

private[ox] enum RestartSelectMarker:
  case RESTART

private[ox] enum SelectState:
  case REGISTERING, INTERRUPTED

private[ox] enum TimeoutMarker:
  case INSTANCE
