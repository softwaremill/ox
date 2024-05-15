package ox.channels

import ox.*

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

trait SourceIOOps[+T]:
  outer: Source[T] =>

  /** Creates a [[java.io.InputStream]] out of this Source. The InputStream can read bytes from the underlying channel. */
  def asInputStream(using T <:< Chunk[Byte]): InputStream = new InputStream:
    private var currentChunk: Iterator[Byte] = Iterator.empty

    override def read(): Int =
      if !currentChunk.hasNext then
        outer.receiveOrClosed() match
          case ChannelClosed.Done     => return -1
          case ChannelClosed.Error(t) => throw t
          case chunk: T @unchecked =>
            currentChunk = chunk.iterator
      currentChunk.next() & 0xff // Convert to unsigned

    override def available: Int =
      currentChunk.length

  /** Writes content of this `Source` to an [[java.io.OutputStream]].
    *
    * @param outputStream
    *   Target `OutputStream` to write to. Will be closed after finishing the process or on error.
    * @throws IOException
    *   if an error occurs when writing or closing of the `OutputStream`.
    */
  def toOutputStream(outputStream: OutputStream)(using T <:< Chunk[Byte], IO): Unit = 
      repeatWhile {
        outer.receiveOrClosed() match
          case ChannelClosed.Done =>
            close(outputStream)
            false
          case ChannelClosed.Error(e) =>
            close(outputStream, Some(e))
            throw e
          case chunk: T @unchecked =>
            try 
              outputStream.write(chunk.toArray)
              true
            catch case NonFatal(e) =>          
              close(outputStream, Some(e))
              throw e                    
      }

  /** Writes content of this `Source` to a file.
    *
    * @param path
    *   Path to the target file. If not exists, it will be created.
    * @throws IOException
    *   if an error occurs when opening the file or during the write process.
    */
  def toFile(path: Path)(using T <:< Chunk[Byte], IO): Unit =
    if Files.isDirectory(path) then throw new IOException(s"Path $path is a directory")
    val jFileChannel =
      try {
        FileChannel.open(path, StandardOpenOption.WRITE)
      } catch
        case _: UnsupportedOperationException =>
          // Some file systems don't support file channels
          Files.newByteChannel(path, StandardOpenOption.WRITE)

    repeatWhile {
      outer.receiveOrClosed() match
        case ChannelClosed.Done =>
          close(jFileChannel)
          false
        case ChannelClosed.Error(e) =>
          close(jFileChannel, Some(e))
          throw e
        case chunk: T @unchecked =>
          try
            jFileChannel.write(ByteBuffer.wrap(chunk.toArray))
            true
          catch case NonFatal(e) =>
            close(jFileChannel, Some(e))
            throw e
    }

  private inline def close(closeable: Closeable, cause: Option[Throwable] = None)(using IO): Unit =
    try 
      closeable.close()
    catch
      case NonFatal(closeException) =>
        cause.foreach(_.addSuppressed(closeException))
        throw cause.getOrElse(closeException)
