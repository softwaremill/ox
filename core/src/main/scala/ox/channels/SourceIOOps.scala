package ox.channels

import ox.*

import java.io.IOException
import java.io.InputStream
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

  def toFile(path: Path)(using T <:< Chunk[Byte]): Unit =
    if Files.isDirectory(path) then throw new IOException(s"Path $path is a directory")
    val jFileChannel = 
      try {
        FileChannel.open(path, StandardOpenOption.WRITE)
      } catch
        case _: UnsupportedOperationException =>
          // Some file systems don't support file channels
          Files.newByteChannel(path, StandardOpenOption.WRITE)
    
    def closeJFileChannel(cause: Option[Throwable]): Unit =
      try jFileChannel.close()
      catch
        case NonFatal(e) =>
          cause.foreach(_.addSuppressed(e))
          throw cause.getOrElse(e)
    repeatWhile {
      outer.receiveOrClosed() match
        case ChannelClosed.Done =>
          closeJFileChannel(None)
          true
        case ChannelClosed.Error(e) =>
          closeJFileChannel(Some(e))
          throw e
        case chunk: T @unchecked =>
          try
            jFileChannel.write(ByteBuffer.wrap(chunk.toArray))
            false
          catch
            case NonFatal(e) =>
              closeJFileChannel(Some(e))
              throw e
    }
