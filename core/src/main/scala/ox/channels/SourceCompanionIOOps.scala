package ox.channels

import ox.*

import java.io.InputStream

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
