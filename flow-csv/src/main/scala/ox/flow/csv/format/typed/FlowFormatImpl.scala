package ox.flow.csv.format.typed

import ox.flow.Flow
import ox.flow.csv.CsvRecord.HeaderRecord
import ox.flow.csv.codec.{CsvRowDecoder, CsvRowEncoder}
import org.apache.commons.csv.CSVFormat as ApacheCSVFormat
import ox.flow.csv.format.{Format, HeaderMode, ParserFormat, PrinterFormat}

import scala.deriving.Mirror.ProductOf

/** Backs every format whose stream may carry a header record: `Flow[HeaderRecord | T]`. */
private[csv] final class OptionalHeaderFormat[T, S](
    apacheFormat: ApacheCSVFormat,
    val builder: TypedFormatBuilder[S],
    rowDecoder: CsvRowDecoder[T],
    rowEncoder: CsvRowEncoder[T]
) extends ParserFormat[Flow[HeaderRecord | T]]
    with PrinterFormat[Flow[HeaderRecord | T]]
    with HasBuilder[S]:

  def parseData(data: Flow[String]): Flow[HeaderRecord | T] =
    ApacheParse.rows(apacheFormat, data).map(rowDecoder.decodeRow)

  /** A [[HeaderRecord]] in the stream is written as its names; everything else as a data row. */
  def writeData(data: Flow[HeaderRecord | T]): Flow[String] =
    ApachePrint.rows(
      apacheFormat,
      data.map:
        case h: HeaderRecord => h.names.toArray[String | Null]
        case t               => rowEncoder.encodeRow(t.asInstanceOf[T])
    )

/** Backs formats that carry no header at all: `Flow[T]`. */
private[csv] final class NoHeaderFormat[T, S](
    apacheFormat: ApacheCSVFormat,
    val builder: TypedFormatBuilder[S],
    rowDecoder: CsvRowDecoder[T],
    rowEncoder: CsvRowEncoder[T]
) extends ParserFormat[Flow[T]]
    with PrinterFormat[Flow[T]]
    with HasBuilder[S]:

  def parseData(data: Flow[String]): Flow[T] =
    ApacheParse.rows(apacheFormat, data).map(rowDecoder.decodeRow)

  def writeData(data: Flow[T]): Flow[String] =
    ApachePrint.rows(apacheFormat, data.map(rowEncoder.encodeRow))

/** Backs printer-only formats that always emit a header: `(HeaderRecord, Flow[T])`. */
private[csv] final class ForcedHeaderFormat[T, S](
    apacheFormat: ApacheCSVFormat,
    val builder: TypedFormatBuilder[S],
    rowEncoder: CsvRowEncoder[T]
) extends PrinterFormat[(HeaderRecord, Flow[T])], HasBuilder[S]:

  /** The header is written first, then the rows. */
  def writeData(data: (HeaderRecord, Flow[T])): Flow[String] =
    val (header, rows) = data
    ApachePrint.rows(
      apacheFormat,
      Flow.fromValues(header.names.toArray[String | Null]).concat(rows.map(rowEncoder.encodeRow))
    )

private[csv] object FlowFormatImpl:

  /** The stream may carry a [[HeaderRecord]]: `Flow[HeaderRecord | T]`.
    *
    * A predefined format knows its own shape statically, so it calls this directly rather than going
    * through [[build]] — an `inline if` cannot reduce while `T` is still abstract in the format's own
    * signature, and the result would widen to the lub of all three shapes.
    */
  inline def optionalHeader[T, S](apacheFormat: ApacheCSVFormat, builder: TypedFormatBuilder[S])(using mir: ProductOf[T]): OptionalHeaderFormat[T, S] =
    new OptionalHeaderFormat[T, S](
      apacheFormat,
      builder,
      CsvRowDecoder[T](SettingsQuery.hasSetting[S, NullString[?]], !SettingsQuery.hasSetting[S, DisallowRaggedRows]),
      CsvRowEncoder[T](SettingsQuery.hasSetting[S, NullString[?]])
    )

  /** No header at all: `Flow[T]`. */
  inline def noHeader[T, S](apacheFormat: ApacheCSVFormat, builder: TypedFormatBuilder[S])(using mir: ProductOf[T]): NoHeaderFormat[T, S] =
    new NoHeaderFormat[T, S](
      apacheFormat,
      builder,
      CsvRowDecoder[T](SettingsQuery.hasSetting[S, NullString[?]], !SettingsQuery.hasSetting[S, DisallowRaggedRows]),
      CsvRowEncoder[T](SettingsQuery.hasSetting[S, NullString[?]])
    )

  /** Printer-only, header always emitted: `(HeaderRecord, Flow[T])`. */
  inline def forcedHeader[T, S](apacheFormat: ApacheCSVFormat, builder: TypedFormatBuilder[S])(using mir: ProductOf[T]): ForcedHeaderFormat[T, S] =
    new ForcedHeaderFormat[T, S](apacheFormat, builder, CsvRowEncoder[T](SettingsQuery.hasSetting[S, NullString[?]]))
