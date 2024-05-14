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

trait SourceCompanionIOOps:

  /** Converts a [[java.io.InputStream]] into a `Source[Chunk[Bytes]]`.
    *
    * @param is
    *   an `InputStream` to read bytes from.
    * @param chunkSize
    *   maximum number of bytes to read from the underlying `InputStream` before emitting a new chunk.
    * @return
    *   a `Source` of chunks of bytes.
    */
  def fromInputStream(is: InputStream, chunkSize: Int = 1024)(using Ox): Source[Chunk[Byte]] =
    val chunks = StageCapacity.newChannel[Chunk[Byte]]
    fork {
      try
        repeatWhile {
          val a = new Array[Byte](chunkSize)
          val r = is.read(a)
          if r == -1 then
            chunks.done()
            false
          else
            val chunk = if r == chunkSize then Chunk.fromArray(a) else Chunk.fromArray(a.take(r))
            chunks.send(chunk)
            true
        }
      catch
        case t: Throwable =>
          chunks.errorOrClosed(t).discard
      finally
        try is.close()
        catch
          case t: Throwable =>
            chunks.errorOrClosed(t).discard
    }
    chunks

    /** Creates a `Source` that emits byte chunks read from a file.
      *
      * @param path
      *   path the file to read from.
      * @param chunkSize
      *   maximum number of bytes to read from the file before emitting a new chunk.
      * @return
      *   a `Source` of chunks of bytes.
      * @throws IOException
      *   If an I/O error occurs when opening the file.
      * @throws SecurityException
      *   If SecurityManager error occurs when opening the file.
      */
  def fromFile(path: Path, chunkSize: Int = 1024)(using Ox): Source[Chunk[Byte]] =
    if Files.isDirectory(path) then throw new IOException(s"Path $path is a directory")
    val chunks = StageCapacity.newChannel[Chunk[Byte]]
    val jFileChannel = useInScope {
      try {
        FileChannel.open(path, StandardOpenOption.READ)
      } catch
        case _: UnsupportedOperationException =>
          // Some file systems don't support file channels
          Files.newByteChannel(path, StandardOpenOption.READ)
    }(_.close())
    fork {
      repeatWhile {
        val buf = ByteBuffer.allocate(chunkSize)
        try {
          val readBytes = jFileChannel.read(buf)
          if readBytes < 0 then
            try
              jFileChannel.close()
              chunks.done()
            catch case NonFatal(closeException) => chunks.errorOrClosed(closeException).discard
            false
          else if readBytes == 0 then
            chunks.send(Chunk.empty)
            true
          else
            chunks.send(Chunk.fromArray(if readBytes == chunkSize then buf.array else buf.array.take(readBytes)))
            true
        } catch
          case e =>
            try
              jFileChannel.close()
              chunks.errorOrClosed(e).discard
            catch case NonFatal(closeException) => chunks.errorOrClosed(closeException).discard
            false
      }
    }
    chunks
