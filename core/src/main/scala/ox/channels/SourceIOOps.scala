package ox.channels

import ox.Chunk

import java.io.InputStream

trait SourceIOOps[+T]:
  outer: Source[T] =>

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
