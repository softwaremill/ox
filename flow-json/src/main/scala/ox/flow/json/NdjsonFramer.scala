package ox.flow.json

import ox.Chunk

import java.io.ByteArrayOutputStream

/** Splits byte chunks into LF-delimited NDJSON records. A CR before the LF stays in the record, a UTF-8 BOM is dropped from the first
  * record, and the final record needs no LF. Stateful, so one instance per flow run.
  *
  * @param maxRecordBytes
  *   maximum size of one record, excluding the LF; a leading BOM and a CR count towards it.
  * @param onRecord
  *   receives one record as the bytes in `[from, to)`; the array is valid only during the call. Not called for records holding only spaces,
  *   tabs and CRs.
  */
private class NdjsonFramer(maxRecordBytes: Int, onRecord: (Array[Byte], Int, Int) => Unit):
  import NdjsonFramer.*

  private val buffer = ByteArrayOutputStream()
  private var firstRecord = true

  /** Reports every record completed by this chunk; buffers the rest. */
  def feed(chunk: Chunk[Byte]): Unit =
    for array <- chunk.backingArrays do
      val bytes = array.unsafeArray
      var recordStart = 0
      var i = 0
      while i < bytes.length do
        if bytes(i) == '\n' then
          completeRecord(bytes, recordStart, i)
          recordStart = i + 1
        i += 1
      append(bytes, recordStart, bytes.length)

  /** Reports the final record, if the input didn't end with an LF. */
  def flush(): Unit =
    if buffer.size() > 0 then emitBuffered()

  // a record contained in one array is reported as a view of it, without copying
  private def completeRecord(bytes: Array[Byte], from: Int, to: Int): Unit =
    requireWithinLimit(to - from)
    if buffer.size() == 0 then emitRecord(bytes, from, to)
    else
      buffer.write(bytes, from, to - from)
      emitBuffered()

  private def emitBuffered(): Unit =
    val bytes = buffer.toByteArray
    buffer.reset()
    emitRecord(bytes, 0, bytes.length)

  private def emitRecord(bytes: Array[Byte], from: Int, to: Int): Unit =
    val start = if firstRecord && startsWithBom(bytes, from, to) then from + utf8Bom.length else from
    firstRecord = false
    if !isBlank(bytes, start, to) then onRecord(bytes, start, to)

  private def isBlank(bytes: Array[Byte], from: Int, to: Int): Boolean =
    var i = from
    while i < to && (bytes(i) == ' ' || bytes(i) == '\t' || bytes(i) == '\r') do i += 1
    i == to

  private def append(bytes: Array[Byte], from: Int, to: Int): Unit =
    requireWithinLimit(to - from)
    buffer.write(bytes, from, to - from)

  private def requireWithinLimit(moreBytes: Int): Unit =
    if buffer.size().toLong + moreBytes > maxRecordBytes then
      throw new IllegalStateException(s"NDJSON record exceeds the maximum of $maxRecordBytes bytes")
end NdjsonFramer

private object NdjsonFramer:
  private val utf8Bom: IArray[Byte] = IArray(0xef.toByte, 0xbb.toByte, 0xbf.toByte)

  private def startsWithBom(bytes: Array[Byte], from: Int, to: Int): Boolean =
    to - from >= utf8Bom.length && bytes(from) == utf8Bom(0) && bytes(from + 1) == utf8Bom(1) && bytes(from + 2) == utf8Bom(2)
