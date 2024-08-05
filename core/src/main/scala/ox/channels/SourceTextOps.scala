package ox.channels

import ox.*

import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import scala.annotation.tailrec

trait SourceTextOps[+T]:
  outer: Source[T] =>

  private val bomUtf8: Chunk[Byte] = Chunk.fromArray(Array[Byte](-17, -69, -65))

  /** Transforms a Source of byte chunks such that each emitted `String` is a text line from the input.
    *
    * @param charset
    *   the charset to use for decoding the bytes into text.
    * @return
    *   a Source emitting lines read from the input byte chunks, assuming they represent text.
    */
  def lines(charset: Charset)(using Ox, T <:< Chunk[Byte]): Source[String] =
    // buffer == null is a special state for handling empty chunks in onComplete, in order to tell them apart from empty lines
    outer
      .mapStatefulConcat(() => null: Chunk[Byte])(
        { case (buffer, nextChunk) =>
          @tailrec
          def splitChunksAtNewLine(buf: Chunk[Byte], chunk: Chunk[Byte], acc: Vector[Chunk[Byte]]): (Chunk[Byte], Vector[Chunk[Byte]]) =
            val newlineIdx = chunk.indexWhere(_ == '\n')
            if newlineIdx == -1 then (buf ++ chunk, acc)
            else
              val (chunk1, chunk2) = chunk.splitAt(newlineIdx)
              splitChunksAtNewLine(Chunk.empty, chunk2.drop(1), acc :+ (buf ++ chunk1))

          val (newBuffer, toEmit) =
            if nextChunk.length == 0 then (null, Vector.empty)
            else splitChunksAtNewLine(if buffer == null then Empty else buffer, nextChunk, Vector.empty)

          (newBuffer, toEmit)
        },
        onComplete = buf => if buf != null then Some(buf) else None
      )
      .mapAsView(_.asString(charset))

  /** Transforms a Source of byte chunks such that each emitted `String` is a text line from the input decoded using UTF-8 charset.
    *
    * @return
    *   a Source emitting lines read from the input byte chunks, assuming they represent text.
    */
  def linesUtf8(using Ox, T <:< Chunk[Byte]): Source[String] =
    lines(StandardCharsets.UTF_8)

  /** Encodes a source of `String` in to a source of bytes using UTF-8. */
  def encodeUtf8(using Ox, T <:< String): Source[Chunk[Byte]] =
    outer.mapAsView(s => Chunk.fromArray(s.getBytes(StandardCharsets.UTF_8)))

  /** Decodes a stream of chunks of bytes into UTF-8 Strings. This function is able to handle UTF-8 characters encoded on multiple bytes
    * that are split across chunks.
    *
    * @return
    *   a source of Strings decoded from incoming bytes.
    */
  def decodeStringUtf8(using Ox, T <:< Chunk[Byte]): Source[String] =
    val bomSize = 3 // const for UTF-8

    // The general algorithm and some helper functions (with their comments) are copied from fs2: see fs2.text.decodeC
    // https://github.com/typelevel/fs2/blob/9b1b27cf7a8d7027df852d890555b341da70ef9e/core/shared/src/main/scala/fs2/text.scala

    /*
     * Copied from fs2 (fs2.text.decodeC.continuationBytes)
     * Returns the number of continuation bytes if `b` is an ASCII byte or a
     * leading byte of a multi-byte sequence, and -1 otherwise.
     */
    def continuationBytes(b: Byte): Int =
      if ((b & 0x80) == 0x00) 0 // ASCII byte
      else if ((b & 0xe0) == 0xc0) 1 // leading byte of a 2 byte seq
      else if ((b & 0xf0) == 0xe0) 2 // leading byte of a 3 byte seq
      else if ((b & 0xf8) == 0xf0) 3 // leading byte of a 4 byte seq
      else -1 // continuation byte or garbage

    /*
     * Copied from fs2 (fs2.text.decodeC.lastIncompleteBytes)
     * Returns the length of an incomplete multi-byte sequence at the end of
     * `bs`. If `bs` ends with an ASCII byte or a complete multi-byte sequence,
     * 0 is returned.
     */
    def lastIncompleteBytes(bs: Array[Byte]): Int = {
      /*
       * This is logically the same as this
       * code, but written in a low level way
       * to avoid any allocations and just do array
       * access
       *
       *
       *
        val lastThree = bs.drop(0.max(bs.size - 3)).toArray.reverseIterator
        lastThree
          .map(continuationBytes)
          .zipWithIndex
          .find {
            case (c, _) => c >= 0
          }
          .map {
            case (c, i) => if (c == i) 0 else i + 1
          }
          .getOrElse(0)

       */

      val minIdx = 0.max(bs.length - 3)
      var idx = bs.length - 1
      var counter = 0
      var res = 0
      while (minIdx <= idx) {
        val c = continuationBytes(bs(idx))
        if (c >= 0) {
          if (c != counter)
            res = counter + 1
          // exit the loop
          return res
        }
        idx = idx - 1
        counter = counter + 1
      }
      res
    }

    def processSingleChunk(buffer: Chunk[Byte], nextBytes: Chunk[Byte]): (String, Chunk[Byte]) =
      // if processing ASCII or largely ASCII buffer is often empty
      val allBytes: Array[Byte] =
        if buffer.isEmpty then nextBytes.toArray
        else Array.concat(buffer.toArray, nextBytes.toArray)

      val splitAt = allBytes.length - lastIncompleteBytes(allBytes)
      if splitAt == allBytes.length then
        // in the common case of ASCII chars
        // we are in this branch so the next buffer will
        // be empty
        (new String(allBytes, StandardCharsets.UTF_8), Chunk.empty)
      else if splitAt == 0 then (null, Chunk.fromArray(allBytes))
      else (new String(allBytes.take(splitAt), StandardCharsets.UTF_8), Chunk.fromArray(allBytes.drop(splitAt)))

    @tailrec
    def doPull(buffer: Chunk[Byte], outputChannel: Channel[String]): Unit =
      outer.receiveOrClosed() match
        case ChannelClosed.Done if buffer.nonEmpty =>
          outputChannel.send(buffer.asStringUtf8)
          outputChannel.done()
        case ChannelClosed.Done =>
          outputChannel.done()
        case ChannelClosed.Error(err) =>
          outputChannel.error(err)
        case bytes: T @unchecked =>
          val (str, newBuf) = processSingleChunk(buffer, bytes)
          if str != null then outputChannel.send(str)
          doPull(newBuf, outputChannel)

    def processByteOrderMark(buffer: Chunk[Byte], outputChannel: Channel[String]): Unit =
      outer.receiveOrClosed() match
        // end of channel before getting enough bytes to resolve BOM, assuming no BOM
        case ChannelClosed.Done =>
          if (buffer != null) then
            // There's a buffer accumulated (not BOM), decode it directly
            outputChannel.send(buffer.asStringUtf8)
          outputChannel.done()
        case ChannelClosed.Error(err) =>
          outputChannel.error(err)
        case bytes: T @unchecked =>
          // A common case, worth checking in advance
          if buffer == null && bytes.length >= bomSize && !bytes.startsWith(bomUtf8) then doPull(bytes, outputChannel)
          else
            val newBuffer0 = if buffer == null then Chunk.empty[Byte] else buffer
            val newBuffer = newBuffer0 ++ bytes
            if (newBuffer.length >= bomSize) then
              val rem = if newBuffer.startsWith(bomUtf8) then newBuffer.drop(bomSize) else newBuffer
              doPull(rem, outputChannel)
            else if (newBuffer.startsWith(bomUtf8.take(newBuffer.length))) then
              processByteOrderMark(newBuffer, outputChannel) // we've accumulated less than the full BOM, let's pull some more
            else // We've accumulated less than BOM size but we already know that these bytes aren't BOM
              doPull(newBuffer, outputChannel)

    val outputChannel = Channel.bufferedDefault[String]
    fork {
      processByteOrderMark(null, outputChannel)
    }
    outputChannel
