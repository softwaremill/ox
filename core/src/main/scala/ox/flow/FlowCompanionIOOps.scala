package ox.flow

import ox.*

import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

trait FlowCompanionIOOps:
  this: FlowCompanionOps =>

  /** Converts a [[java.io.InputStream]] into a `Flow[Chunk[Bytes]]`.
    *
    * @param is
    *   an `InputStream` to read bytes from.
    * @param chunkSize
    *   maximum number of bytes to read from the underlying `InputStream` before emitting a new chunk.
    */
  def fromInputStream(is: InputStream, chunkSize: Int = 1024): Flow[Chunk[Byte]] = usingSinkInline: sink =>
    try
      repeatWhile:
        val buf = new Array[Byte](chunkSize)
        val readBytes = is.read(buf)
        if readBytes == -1 then false
        else
          if readBytes > 0 then sink.apply(if readBytes == chunkSize then Chunk.fromArray(buf) else Chunk.fromArray(buf.take(readBytes)))
          true
    finally is.close()
  end fromInputStream

  /** Creates a flow that emits byte chunks read from a file.
    *
    * @param path
    *   path the file to read from.
    * @param chunkSize
    *   maximum number of bytes to read from the file before emitting a new chunk.
    */

  def fromFile(path: Path, chunkSize: Int = 1024): Flow[Chunk[Byte]] = usingSinkInline: sink =>
    if Files.isDirectory(path) then throw new IOException(s"Path $path is a directory")
    val jFileChannel =
      try FileChannel.open(path, StandardOpenOption.READ)
      catch
        case _: UnsupportedOperationException =>
          // Some file systems don't support file channels
          Files.newByteChannel(path, StandardOpenOption.READ)

    try
      repeatWhile:
        val buf = ByteBuffer.allocate(chunkSize)
        val readBytes = jFileChannel.read(buf)
        if readBytes < 0 then false
        else
          if readBytes > 0 then sink.apply(Chunk.fromArray(if readBytes == chunkSize then buf.array else buf.array.take(readBytes)))
          true
    finally jFileChannel.close()
    end try
  end fromFile
end FlowCompanionIOOps
