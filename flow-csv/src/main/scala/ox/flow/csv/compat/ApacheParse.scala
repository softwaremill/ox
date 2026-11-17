package ox.flow.csv.compat

import org.apache.commons.csv.CSVFormat as ApacheCSVFormat
import ox.channels.{BufferCapacity, ChannelClosedException}
import ox.flow.Flow
import ox.flow.csv.ChannelReader
import ox.unsupervised

import scala.jdk.CollectionConverters.*

private[csv] object ApacheParse:

  /** Runs the text through Apache's parser, emitting one row per record.
    *
    * A cell is `null` when it matched the format's null string; a row shorter than the record type's field
    * count means the trailing fields are missing. Both are left for the row decoder to resolve.
    *
    * Creates an asynchronous boundary: the input is run in the background, so text is consumed ahead of
    * downstream demand, up to the [[BufferCapacity]] in scope.
    */
  def rows(format: RawCsvFormat, data: Flow[String])(using BufferCapacity): Flow[Array[String | Null]] =
    val apacheFormat = format.underlying
    Flow.usingEmit: emit =>
      // the body of `unsupervised` runs on the calling thread, so `emit` is called from it as well (as required),
      // while the flow producing the text is run in a fork by `runToChannel`
      unsupervised:
        try
          val parser = apacheFormat.parse(ChannelReader(data.runToChannel()))
          try
            parser
              .iterator()
              .asScala
              .foreach: record =>
                val row = new Array[String | Null](record.size)
                var i = 0
                while i < row.length do
                  row(i) = record.get(i)
                  i += 1
                emit(row)
          finally parser.close()
          // the channel wraps a failure of `data`; unwrapped, it propagates as-is
        catch case ChannelClosedException.Error(reason) => throw reason
