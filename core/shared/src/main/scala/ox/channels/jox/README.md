# Scala Port of Jox Channels

Pure Scala port of [softwaremill/jox](https://github.com/softwaremill/jox) `channels` module,
enabling cross-compilation to Scala Native.

## Source Version

Ported from: **jox 1.1.2** (`v1.1.2-channels` tag)

## Target Platform

Requires: **Scala Native 0.5.12+** (for virtual threads, `java.util.concurrent` atomics, `LockSupport`)

## Divergences from Java Jox

### 1. AtomicXxx instead of VarHandle

Java Jox uses `java.lang.invoke.VarHandle` for all atomic field and array operations.
This port uses:

| Java Jox | Scala Port | Reason |
|----------|-----------|--------|
| `VarHandle` on `long` fields | `AtomicLong` | VarHandle is a stub in Scala Native (not implemented) |
| `VarHandle` on reference fields | `AtomicReference[T]` | Same |
| `VarHandle` on `int` fields | `AtomicInteger` | Same |
| `MethodHandles.arrayElementVarHandle` | `AtomicReferenceArray` | Same |

**Impact**: Slightly more indirection (fields are objects rather than plain volatiles with CAS via handles).
Performance difference is negligible for virtual-thread-based workloads.

**Removal condition**: If Scala Native implements `java.lang.invoke.VarHandle` with `findVarHandle`
and `arrayElementVarHandle`, this port could switch back to VarHandle for parity.

### 2. Segment.findAndMoveForward signature

Java Jox passes a `VarHandle` + owning object to `findAndMoveForward` / `moveForward` for generic
field updates. The Scala port passes `AtomicReference[Segment]` directly.

**Impact**: None on behavior. Slightly less generic but type-safe.

### 3. No forEach / toList on Source

The Java `Source` interface has default `forEach` and `toList` methods. These are omitted because
the Ox wrapper (`ox.channels.SourceOps` / `SourceDrainOps`) provides equivalent functionality
with better Scala ergonomics.

### 4. No Sink.trySend(value, channels...) static method

The Java `Sink` has a static `trySend` that selects across multiple sinks. Omitted because
Ox exposes this via its own select API.

### 5. Channel.toString is simplified

The Java version prints full segment-by-segment cell state. The Scala port returns a short
`Channel(capacity=N)` string. The verbose version can be added if needed for debugging.

## File Mapping

| Java Jox file | Scala port file |
|--------------|-----------------|
| `Channel.java` (1640 lines) | `Channel.scala` |
| `Segment.java` | `Segment.scala` |
| `Select.java` | `Select.scala` (includes `SelectInstance`) |
| `SelectClause.java` | `SelectClause.scala` |
| `Source.java` | `Source.scala` |
| `Sink.java` | `Sink.scala` |
| `CloseableChannel.java` | `CloseableChannel.scala` |
| `ChannelClosed.java`, `ChannelDone.java`, `ChannelError.java` | `ChannelClosed.scala` |
| `ChannelClosedException.java`, `*Exception.java` | `ChannelClosed.scala` |
| (inner classes in Channel.java) | `CellState.scala` |
| (inner class in Select.java) | `StoredSelectClause.scala` |
| (inner class in Channel.java) | `Continuation.scala` |
