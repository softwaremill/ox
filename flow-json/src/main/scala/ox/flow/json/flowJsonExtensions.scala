package ox.flow.json

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.core.ReaderConfig
import com.github.plokhotnyuk.jsoniter_scala.core.WriterConfig
import com.github.plokhotnyuk.jsoniter_scala.core.readFromSubArray
import com.github.plokhotnyuk.jsoniter_scala.core.scanJsonArrayFromStreamReentrant
import com.github.plokhotnyuk.jsoniter_scala.core.writeToArray
import ox.Chunk
import ox.channels.BufferCapacity
import ox.channels.ChannelClosed
import ox.channels.ChannelClosedException
import ox.channels.forkPropagate
import ox.discard
import ox.flow.Flow
import ox.repeatWhile
import ox.supervised
import ox.unsupervised

import java.util.Arrays

private val arrayEnd: Chunk[Byte] = Chunk.fromArray(Array[Byte](']'))
private val emptyArray: Chunk[Byte] = Chunk.fromArray(Array[Byte]('[', ']'))

// the byte and the value go into one array, as sinks write one backing array at a time
private def chunkPrefixed(prefix: Byte, json: Array[Byte]): Chunk[Byte] =
  val bytes = new Array[Byte](json.length + 1)
  bytes(0) = prefix
  System.arraycopy(json, 0, bytes, 1, json.length)
  Chunk.fromArray(bytes)

private def chunkSuffixed(json: Array[Byte], suffix: Byte): Chunk[Byte] =
  val bytes = Arrays.copyOf(json, json.length + 1)
  bytes(json.length) = suffix
  Chunk.fromArray(bytes)

extension (flow: Flow[Chunk[Byte]])
  /** Parses UTF-8 NDJSON: one JSON value per LF-delimited line. Lines holding only spaces, tabs and CRs are skipped, and CRLF endings, a
    * final record without an LF and one leading byte-order mark are accepted. With the default `ReaderConfig`, content after the value of a
    * record fails the flow.
    *
    * @param maxRecordBytes
    *   maximum size of one record, excluding the LF; a leading BOM and a CR count towards it. 32 MiB by default. Must be positive; a longer
    *   record fails the flow with an `IllegalStateException`.
    * @param config
    *   the jsoniter configuration used to read each record.
    * @return
    *   a flow emitting one value per non-blank input line.
    */
  def parseNdjson[T: JsonValueCodec](maxRecordBytes: Int = 32 * 1024 * 1024, config: ReaderConfig = ReaderConfig): Flow[T] =
    require(maxRecordBytes > 0, "maxRecordBytes must be > 0")
    Flow.usingEmit: emit =>
      val framer = NdjsonFramer(maxRecordBytes, (bytes, from, to) => emit(readFromSubArray[T](bytes, from, to, config)))
      flow.runForeach(framer.feed)
      framer.flush()

  /** Parses exactly one top-level UTF-8 JSON array, emitting its elements as they are parsed. A byte-order mark is not stripped. An
    * incomplete array and a non-array top-level value fail the flow; a top-level `null` is accepted as an empty array. With the default
    * `ReaderConfig`, content after the array fails the flow, so the flow completes only once the source ends.
    *
    * Creates an asynchronous boundary: the input is read through [[ox.flow.FlowIOOps.runToInputStream]], so chunks are consumed ahead of
    * downstream demand. Elements are buffered using a buffer of capacity given by the [[BufferCapacity]] in scope.
    *
    * @param config
    *   the jsoniter configuration used to read the array.
    * @return
    *   a flow emitting the array's elements.
    */
  def parseJsonArray[T: JsonValueCodec](config: ReaderConfig = ReaderConfig)(using BufferCapacity): Flow[T] =
    Flow.usingEmit: emit =>
      val elements = BufferCapacity.newChannel[T]
      def sendElement(t: T): Boolean =
        elements.send(t)
        true // keep scanning
      unsupervised:
        forkPropagate(elements):
          // runToInputStream needs a supervised scope
          supervised:
            val in = flow.runToInputStream()
            try scanJsonArrayFromStreamReentrant[T](in, config)(sendElement)
            // the input stream wraps a failure of `flow`; unwrapped, it propagates as-is
            catch case ChannelClosedException.Error(reason) => throw reason
          elements.doneOrClosed().discard
        // receiving in the main body, so that `emit` runs on the calling thread; not using FlowEmit.channelToEmit,
        // as that wraps the error in a ChannelClosedException, while here failures propagate as-is
        repeatWhile:
          elements.receiveOrClosed() match
            case ChannelClosed.Done          => false
            case ChannelClosed.Error(reason) => throw reason
            case element: T @unchecked       => emit(element); true
end extension

extension [T](flow: Flow[T])
  /** Renders each value as UTF-8 JSON followed by an LF, including the last one.
    *
    * @param config
    *   the jsoniter configuration used to write each value. Must not indent, as NDJSON consumers split records by line.
    * @return
    *   a flow emitting one chunk per value, holding the value's JSON and the LF.
    */
  def renderNdjson(config: WriterConfig = WriterConfig)(using JsonValueCodec[T]): Flow[Chunk[Byte]] =
    require(config.indentionStep == 0, "indented JSON cannot be rendered as NDJSON")
    flow.map(value => chunkSuffixed(writeToArray(value, config), '\n'))

  /** Renders the values as one UTF-8 JSON array. An empty flow renders as `[]`; if the flow fails after output has started, the output is
    * left as an incomplete array.
    *
    * @param config
    *   the jsoniter configuration used to write each element.
    * @return
    *   a flow emitting one chunk per value, holding the value's separator and JSON, followed by a chunk with the closing bracket.
    */
  def renderJsonArray(config: WriterConfig = WriterConfig)(using JsonValueCodec[T]): Flow[Chunk[Byte]] =
    flow.mapStateful(true)(
      (first, value) => (false, chunkPrefixed(if first then '[' else ',', writeToArray(value, config))),
      onComplete = first => Some(if first then emptyArray else arrayEnd)
    )
end extension
