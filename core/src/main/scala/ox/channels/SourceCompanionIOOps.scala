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

  /** Converts a [[java.io.InputStream]] into a `Source[Chunk[Bytes]]`. Implicit `StageCapacity` can be used to control the number of
    * buffered chunks.
    *
    * @param is
    *   an `InputStream` to read bytes from.
    * @param chunkSize
    *   maximum number of bytes to read from the underlying `InputStream` before emitting a new chunk.
    * @return
    *   a `Source` of chunks of bytes.
    */
  def fromInputStream(is: InputStream, chunkSize: Int = 1024)(using Ox, StageCapacity): Source[Chunk[Byte]] =
    val chunks = StageCapacity.newChannel[Chunk[Byte]]
    fork {
      try
        repeatWhile {
          val buf = new Array[Byte](chunkSize)
          val readBytes = is.read(buf)
          if readBytes == -1 then
            chunks.done()
            false
          else
            if readBytes > 0 then
              chunks.send(if readBytes == chunkSize then Chunk.fromArray(buf) else Chunk.fromArray(buf.take(readBytes)))
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

    /** Creates a `Source` that emits byte chunks read from a file. Implicit `StageCapacity` can be used to control the number of buffered
      * chunks.
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
  def fromFile(path: Path, chunkSize: Int = 1024)(using Ox, StageCapacity): Source[Chunk[Byte]] =
    if Files.isDirectory(path) then throw new IOException(s"Path $path is a directory")
    val chunks = StageCapacity.newChannel[Chunk[Byte]]
    val jFileChannel =
      try FileChannel.open(path, StandardOpenOption.READ)
      catch
        case _: UnsupportedOperationException =>
          // Some file systems don't support file channels
          Files.newByteChannel(path, StandardOpenOption.READ)

    fork {
      try {
        repeatWhile {
          val buf = ByteBuffer.allocate(chunkSize)
          val readBytes = jFileChannel.read(buf)
          if readBytes < 0 then
            chunks.done()
            false
          else
            if readBytes > 0 then chunks.send(Chunk.fromArray(if readBytes == chunkSize then buf.array else buf.array.take(readBytes)))
            true
        }
      } catch case e => chunks.errorOrClosed(e).discard
      finally
        try jFileChannel.close()
        catch
          case NonFatal(closeException) =>
            chunks.errorOrClosed(closeException).discard
    }
    chunks
