package ox.channels.jox

// Possible states of a cell: one of these enum constants, Continuation, StoredSelectClause, or a buffered value
enum CellState:
  case DONE
  case INTERRUPTED_SEND // the send/receive differentiation is important for expandBuffer
  case INTERRUPTED_RECEIVE
  case BROKEN
  case IN_BUFFER // used to inform a potentially concurrent sender that the cell is now in the buffer
  case RESUMING // expandBuffer is resuming a sender
  case CLOSED

enum SendResult:
  case AWAITED, BUFFERED, RESUMED, FAILED, CLOSED

enum ReceiveResult:
  case FAILED, CLOSED

enum ExpandBufferResult:
  case DONE, FAILED, CLOSED

enum ContinuationMarker:
  case INTERRUPTED

enum ChannelClosedMarker:
  case CLOSED

enum SentClauseMarker:
  case SENT

enum RestartSelectMarker:
  case RESTART

enum SelectState:
  case REGISTERING, INTERRUPTED

enum TimeoutMarker:
  case INSTANCE
