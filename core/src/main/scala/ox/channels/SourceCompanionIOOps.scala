package ox.channels

import ox.*

import java.io.InputStream

trait SourceCompanionIOOps:
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
          chunks.errorOrClosed(t)
      finally
        try is.close()
        catch case _: Throwable => ()
    }
    chunks
