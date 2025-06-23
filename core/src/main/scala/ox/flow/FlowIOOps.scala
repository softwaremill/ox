package ox.flow

import ox.*
import ox.channels.ChannelClosed
import ox.channels.BufferCapacity

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import scala.util.control.NonFatal

trait FlowIOOps[+T]:
  outer: Flow[T] =>

  /** Runs the flow into a [[java.io.InputStream]].
    *
    * Must be run within a concurrency scope, as under the hood the flow is run in the background.
    */
  def runToInputStream()(using T <:< Chunk[Byte])(using Ox, BufferCapacity): InputStream =
    val ch = this.runToChannel()
    new InputStream:
      // Current state for efficient reading from backing arrays
      private var currentArrays: List[IArray[Byte]] = Nil
      private var currentArrayIndex: Int = 0
      private var currentByteIndex: Int = 0
      private var availableBytes: Int = 0
      private var isEndOfStream: Boolean = false

      /** Ensure we have data available to read. Returns false if no more data is available. */
      private def ensureDataAvailable(): Boolean =
        // Keep trying to find a non-empty array with available data
        while currentArrays.nonEmpty && currentArrayIndex < currentArrays.length do
          val currentArray = currentArrays(currentArrayIndex)
          if currentByteIndex < currentArray.length then return true

          // Current array is exhausted or empty, move to next array
          currentArrayIndex += 1
          currentByteIndex = 0

        // No more data in current arrays, try to get next chunk
        if !isEndOfStream then
          ch.receiveOrClosed() match
            case ChannelClosed.Done =>
              isEndOfStream = true
              availableBytes = 0 // No more data available
              false
            case e: ChannelClosed.Error =>
              throw e.toThrowable
            case chunk: T @unchecked =>
              currentArrays = chunk.backingArrays
              currentArrayIndex = 0
              currentByteIndex = 0
              // Calculate total bytes from all non-empty arrays
              availableBytes = currentArrays.map(_.length).sum
              // If this chunk has no actual data, try again recursively
              ensureDataAvailable()
        else false
        end if
      end ensureDataAvailable

      override def read(): Int =
        if !ensureDataAvailable() then -1
        else
          val currentArray = currentArrays(currentArrayIndex)
          val byte = currentArray(currentByteIndex) & 0xff // Convert to unsigned
          currentByteIndex += 1
          availableBytes -= 1
          byte

      override def read(b: Array[Byte], off: Int, len: Int): Int =
        if b == null then throw new NullPointerException
        if off < 0 || len < 0 || len > b.length - off then throw new IndexOutOfBoundsException
        if len == 0 then return 0

        var totalBytesRead = 0
        var remaining = len
        var offset = off

        while remaining > 0 && ensureDataAvailable() do
          val currentArray = currentArrays(currentArrayIndex)
          val availableInCurrentArray = currentArray.length - currentByteIndex
          val bytesToRead = math.min(remaining, availableInCurrentArray)

          // Copy bytes from current array to target array
          System.arraycopy(currentArray.unsafeArray, currentByteIndex, b, offset, bytesToRead)

          currentByteIndex += bytesToRead
          offset += bytesToRead
          remaining -= bytesToRead
          totalBytesRead += bytesToRead
          availableBytes -= bytesToRead

          // If we've exhausted current array, move to next one
          if currentByteIndex >= currentArray.length then
            currentArrayIndex += 1
            currentByteIndex = 0
        end while

        // Return -1 if no bytes were read and stream is at end-of-file
        if totalBytesRead == 0 then -1 else totalBytesRead
      end read

      override def available: Int = availableBytes
    end new
  end runToInputStream

  /** Writes content of this flow to an [[java.io.OutputStream]].
    *
    * @param outputStream
    *   Target `OutputStream` to write to. Will be closed after finishing the process or on error.
    */
  def runToOutputStream(outputStream: OutputStream)(using T <:< Chunk[Byte]): Unit =
    try
      runForeach(chunk => chunk.backingArrays.foreach(arr => outputStream.write(arr.unsafeArray)))
      close(outputStream)
    catch
      case t: Throwable =>
        close(outputStream, Some(t))
        throw t
    end try
  end runToOutputStream

  /** Writes content of this flow to a file.
    *
    * @param path
    *   Path to the target file. If not exists, it will be created.
    */
  def runToFile(path: Path)(using T <:< Chunk[Byte]): Unit =
    if Files.isDirectory(path) then throw new IOException(s"Path $path is a directory")
    val jFileChannel =
      try FileChannel.open(path, StandardOpenOption.WRITE, StandardOpenOption.CREATE)
      catch
        case _: UnsupportedOperationException =>
          // Some file systems don't support file channels
          Files.newByteChannel(path, StandardOpenOption.WRITE, StandardOpenOption.CREATE)

    try
      runForeach(chunk => chunk.backingArrays.foreach(array => jFileChannel.write(ByteBuffer.wrap(array.unsafeArray)).discard))
      close(jFileChannel)
    catch
      case t: Throwable =>
        close(jFileChannel, Some(t))
        throw t
  end runToFile

  private inline def close(closeable: Closeable, cause: Option[Throwable] = None): Unit =
    try closeable.close()
    catch
      case NonFatal(closeException) =>
        cause.foreach(_.addSuppressed(closeException))
        throw cause.getOrElse(closeException)
end FlowIOOps
