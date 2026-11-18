package ox.flow.csv.internal

import ox.channels.{ChannelClosed, Source}

import java.io.Reader

private[csv] class ChannelReader(ch: Source[String]) extends Reader:
  private var buffer = ""
  private var bufferPos = 0
  private var done = false

  override def read(cbuf: Array[Char], off: Int, len: Int): Int =
    def bufferExhausted = bufferPos >= buffer.length

    if len == 0 then 0
    else
      while bufferExhausted && !done do
        ch.receiveOrClosed() match
          case ChannelClosed.Done     => done = true
          case e: ChannelClosed.Error => throw e.toThrowable
          case s: String              =>
            buffer = s
            bufferPos = 0
      if done then -1
      else
        val numberOfCharsToRead = math.min(len, buffer.length - bufferPos)
        buffer.getChars(bufferPos, bufferPos + numberOfCharsToRead, cbuf, off)
        bufferPos += numberOfCharsToRead
        numberOfCharsToRead
    end if
  end read

  override def close(): Unit = ()
end ChannelReader
