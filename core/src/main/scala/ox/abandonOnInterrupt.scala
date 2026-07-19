package ox

import ox.channels.Channel
import ox.channels.ChannelClosed

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.atomic.AtomicReference

/** Runs `op` on a newly-started detached (unmanaged, never-joined) virtual thread, and awaits its result interruptibly. If the calling
  * thread is interrupted, the wait is abandoned: an [[InterruptedException]] is thrown, and `op`'s eventual result or exception is
  * discarded — note that `op` keeps running, side effects included. If `op` throws while the caller is still waiting, the exception is
  * re-thrown to the caller.
  *
  * As `op` runs outside of any concurrency scope, it must not use scope capabilities (such as forking), and [[ForkLocal]] values are not
  * propagated.
  *
  * Use for blocking operations which don't respond to interruption, and can't be unblocked by closing an underlying resource (see the
  * variant with an `onAbandon` parameter otherwise).
  */
def abandonOnInterrupt[T](op: => T): T = abandonOnInterrupt(op)(())

/** As [[abandonOnInterrupt]]; additionally, when the wait is abandoned, `onAbandon` is started fire-and-forget on another detached thread —
  * never on the interrupted caller, as cleanup (e.g. closing a resource) might itself block. Exceptions thrown by `onAbandon` are
  * discarded.
  *
  * Use `onAbandon` to release the underlying resource, e.g. `abandonOnInterrupt(statement.execute())(connection.close())`: for resources
  * which support asynchronous close, this actively unblocks the abandoned operation as well.
  */
def abandonOnInterrupt[T](op: => T)(onAbandon: => Unit): T =
  val result = new CompletableFuture[T]()
  startDetachedThread("run") {
    try result.complete(op).discard
    catch case t: Throwable => result.completeExceptionally(t).discard
  }
  try result.get()
  catch
    // a direct InterruptedException means the caller was interrupted while waiting: the wait is abandoned; an
    // ExecutionException-wrapped one is a failure of `op` itself, re-thrown below like any other op exception
    case e: InterruptedException =>
      fireAndForget("on-abandon")(onAbandon)
      throw e
    case e: ExecutionException =>
      val cause = e.getCause
      cause.addSuppressed(e) // so that no context is lost
      throw cause
  end try
end abandonOnInterrupt

private[ox] def startDetachedThread(name: String)(body: => Unit): Unit =
  Thread.ofVirtual().name(s"ox-detached-$name").start(() => body).discard

/** Runs `body` on a detached thread, discarding any exceptions it throws: used for cleanup which must never block or fail the initiating
  * (typically: just-interrupted) thread — e.g. closing a resource, which might itself block.
  */
private[ox] def fireAndForget(name: String)(body: => Unit): Unit =
  startDetachedThread(name) {
    try body
    catch case _: Throwable => ()
  }

private def asIOException(t: Throwable): IOException = t match
  case io: IOException => io
  case _               => new IOException(t)

/** Wraps `is` so that reads become interruptible: a detached virtual thread reads chunks of up to `chunkSize` bytes from `is`, passing them
  * to the returned stream via a rendezvous channel (no read-ahead beyond the single in-flight chunk). An interrupted read abandons the
  * wait: the in-flight chunk is not lost, and is returned by the next read.
  *
  * With `closeOnAbandon = true`, an interrupted read additionally closes `is` (fire-and-forget, on a detached thread), and permanently
  * closes the returned stream.
  *
  * Closing the returned stream closes `is`; note that the detached thread might not terminate immediately: it may stay blocked in its
  * current read of `is` until that read returns (closing e.g. a [[java.io.FileInputStream]] does not unblock a pending read), or remain
  * parked holding an already-read chunk which won't be received anymore.
  *
  * The returned stream is not thread-safe (single reader). For stdin, create a single process-wide wrapper (multiple wrappers would compete
  * for input).
  */
def abandonOnInterruptReads(is: InputStream, chunkSize: Int = 8192, closeOnAbandon: Boolean = false): InputStream =
  require(chunkSize > 0, "chunkSize must be positive")
  val chan = Channel.rendezvous[Chunk[Byte]]

  startDetachedThread("reads") {
    try
      var running = true
      while running do
        val buf = new Array[Byte](chunkSize)
        val n = is.read(buf)
        if n == -1 then
          chan.doneOrClosed().discard
          running = false
        // the n > 0 guard (instead of a plain else) prevents sending empty chunks, should a contract-violating
        // stream return 0 for a non-empty buffer
        else if n > 0 then
          // avoiding a copy when the buffer came back full (a fresh buffer is allocated each iteration)
          val chunk = Chunk.fromArray(if n == chunkSize then buf else buf.take(n))
          if chan.sendOrClosed(chunk).isInstanceOf[ChannelClosed] then running = false
        end if
      end while
    catch case t: Throwable => chan.errorOrClosed(t).discard
  }

  new InputStream:
    private var current: Chunk[Byte] = Chunk.empty
    private var pos = 0
    private var eof = false
    private var closed = false

    // returns false on EOF; after returning true, at least one byte is available in `current`
    private def ensureAvailable(): Boolean =
      if closed then throw new IOException("Stream closed")
      else if eof then false
      else if pos < current.length then true
      else
        try
          chan.receiveOrClosed() match
            case ChannelClosed.Done     => eof = true; false
            case ChannelClosed.Error(t) => throw asIOException(t)
            case c                      => current = c.asInstanceOf[Chunk[Byte]]; pos = 0; true
        catch
          case e: InterruptedException =>
            // the interrupt arrived before the rendezvous completed, so the detached thread still holds any in-flight
            // chunk; unless we're closing, it will be received by the next read
            if closeOnAbandon then
              closed = true
              chan.doneOrClosed().discard
              fireAndForget("reads-close")(is.close())
            throw e

    override def read(): Int =
      if !ensureAvailable() then -1
      else
        val b = current(pos) & 0xff
        pos += 1
        b

    override def read(b: Array[Byte], off: Int, len: Int): Int =
      java.util.Objects.checkFromIndexSize(off, len, b.length)
      if len == 0 then 0
      else if !ensureAvailable() then -1
      else
        val n = math.min(len, current.length - pos)
        var i = 0
        while i < n do
          b(off + i) = current(pos + i)
          i += 1
        pos += n
        n
      end if
    end read

    override def available(): Int = if closed || eof then 0 else current.length - pos

    override def close(): Unit =
      if !closed then
        closed = true
        chan.doneOrClosed().discard // signals the detached thread to stop at its next send
        is.close()
  end new
end abandonOnInterruptReads

private enum WriteCommand:
  case Write(chunk: Chunk[Byte])
  case Flush(ack: CompletableFuture[Unit])
  case Close(ack: CompletableFuture[Unit])

/** Wraps `os` so that writes and flushes become interruptible: a detached virtual thread performs the actual writes. Writes are passed
  * through a rendezvous channel, providing backpressure: if a write to `os` blocks, the wrapper's operations eventually block as well — but
  * interruptibly. `flush()` and `close()` await completion by the detached thread, interruptibly; `close()` closes `os` after completing
  * the writes queued before it. If an operation is abandoned before its command is handed over to the detached thread (and `closeOnAbandon`
  * is not set), `os` remains open, and `close()` can be retried.
  *
  * An error thrown by an (asynchronously performed) underlying write surfaces, as an [[IOException]], on the next write/flush/close call;
  * the wrapper is then permanently broken.
  *
  * An interrupted operation abandons the wait. With `closeOnAbandon = true`, it additionally closes `os` (fire-and-forget, on a detached
  * thread), and permanently closes the returned stream.
  *
  * The returned stream is not thread-safe (single writer).
  */
def abandonOnInterruptWrites(os: OutputStream, closeOnAbandon: Boolean = false): OutputStream =
  val chan = Channel.rendezvous[WriteCommand]
  val pendingError = new AtomicReference[Throwable]()

  startDetachedThread("writes") {
    var running = true
    while running do
      chan.receiveOrClosed() match
        case WriteCommand.Write(chunk) =>
          if pendingError.get() == null then
            try os.write(chunk.toArray)
            catch case t: Throwable => pendingError.set(t)
        case WriteCommand.Flush(ack) =>
          pendingError.get() match
            case null =>
              try
                os.flush(); ack.complete(()).discard
              catch
                case t: Throwable =>
                  pendingError.set(t)
                  ack.completeExceptionally(t).discard
            case t => ack.completeExceptionally(t).discard
        case WriteCommand.Close(ack) =>
          // a pending write error surfaces on close as well, so that a successful close guarantees all writes landed
          val closeError = try
            os.close(); null
          catch case t: Throwable => t
          val error = if pendingError.get() != null then pendingError.get() else closeError
          if error == null then ack.complete(()).discard else ack.completeExceptionally(error).discard
          running = false
        case _: ChannelClosed => running = false
    end while
  }

  new OutputStream:
    private var closed = false

    private def checkUsable(): Unit =
      if closed then throw new IOException("Stream closed")
      pendingError.get() match
        case null => ()
        case t    => throw asIOException(t)

    // runs op; if the wait is abandoned (interrupted), performs the close-on-abandon cleanup & rethrows
    private def abandoning[T](op: => T, alreadyClosing: Boolean = false): T =
      try op
      catch
        case e: InterruptedException =>
          if closeOnAbandon then
            closed = true
            chan.doneOrClosed().discard
            // when abandoning close()'s await, the detached thread is already executing the delivered Close command —
            // closing again would run two concurrent os.close() calls
            if !alreadyClosing then fireAndForget("writes-close")(os.close())
          throw e

    private def sendCommand(cmd: WriteCommand): Unit =
      abandoning {
        if chan.sendOrClosed(cmd).isInstanceOf[ChannelClosed] then throw new IOException("Stream closed")
      }

    private def await(ack: CompletableFuture[Unit], alreadyClosing: Boolean = false): Unit =
      abandoning(
        {
          try unwrapExecutionException(ack.get())
          catch
            case e: IOException          => throw e
            case e: InterruptedException => throw e
            case t: Throwable            => throw new IOException(t)
        },
        alreadyClosing
      )

    override def write(b: Int): Unit = write(Array[Byte](b.toByte), 0, 1)

    override def write(b: Array[Byte], off: Int, len: Int): Unit =
      java.util.Objects.checkFromIndexSize(off, len, b.length)
      checkUsable()
      if len > 0 then sendCommand(WriteCommand.Write(Chunk.fromArray(b.slice(off, off + len))))

    override def flush(): Unit =
      checkUsable()
      val ack = new CompletableFuture[Unit]()
      sendCommand(WriteCommand.Flush(ack))
      await(ack)

    override def close(): Unit =
      if !closed then
        val ack = new CompletableFuture[Unit]()
        sendCommand(WriteCommand.Close(ack))
        closed = true
        await(ack, alreadyClosing = true)
  end new
end abandonOnInterruptWrites
