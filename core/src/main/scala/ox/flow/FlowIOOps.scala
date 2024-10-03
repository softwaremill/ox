package ox.flow

import ox.*
import ox.channels.ChannelClosed
import ox.channels.StageCapacity

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

  /** Creates a [[java.io.InputStream]] out of this flow. The InputStream can read bytes from the underlying channel. */
  def runToInputStream()(using T <:< Chunk[Byte])(using Ox, StageCapacity): InputStream =
    val ch = this.runToChannel()
    new InputStream:
      private var currentChunk: Iterator[Byte] = Iterator.empty

      override def read(): Int =
        if !currentChunk.hasNext then
          ch.receiveOrClosed() match
            case ChannelClosed.Done     => return -1
            case ChannelClosed.Error(t) => throw t
            case chunk: T @unchecked =>
              currentChunk = chunk.iterator
        currentChunk.next() & 0xff // Convert to unsigned

      override def available: Int =
        currentChunk.length
    end new
  end runToInputStream

  /** Writes content of this flow to an [[java.io.OutputStream]].
    *
    * @param outputStream
    *   Target `OutputStream` to write to. Will be closed after finishing the process or on error.
    */
  def runToOutputStream(outputStream: OutputStream)(using T <:< Chunk[Byte]): Unit =
    try
      runForeach(chunk => outputStream.write(chunk.toArray))
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
      try FileChannel.open(path, StandardOpenOption.WRITE)
      catch
        case _: UnsupportedOperationException =>
          // Some file systems don't support file channels
          Files.newByteChannel(path, StandardOpenOption.WRITE)

    try
      runForeach(chunk => jFileChannel.write(ByteBuffer.wrap(chunk.toArray)).discard)
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
