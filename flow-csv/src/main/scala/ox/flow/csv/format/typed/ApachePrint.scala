package ox.flow.csv.format.typed

import ox.flow.Flow
import org.apache.commons.csv.CSVFormat as ApacheCSVFormat

import java.io.StringWriter

private[csv] object ApachePrint:

  /** Renders each row through Apache's printer, emitting one string per record.
    *
    * A `null` cell is written as the format's null string. The record separator is included, so the strings
    * concatenate into a well-formed document.
    *
    * One printer and one buffer are reused across the whole stream: `CSVFormat.format` would allocate both
    * per record.
    */
  def rows(apacheFormat: ApacheCSVFormat, data: Flow[Array[String | Null]]): Flow[String] =
    Flow.usingEmit: emit =>
      val buffer = StringWriter()
      val printer = apacheFormat.print(buffer)
      try
        data.runToEmit: row =>
          printer.printRecord(row*)
          printer.flush()
          emit(buffer.toString)
          buffer.getBuffer.setLength(0)
      finally printer.close()
