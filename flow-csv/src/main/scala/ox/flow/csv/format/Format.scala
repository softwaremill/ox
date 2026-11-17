package ox.flow.csv.format

import org.apache.commons.csv.CSVFormat as ApacheCSVFormat
import ox.flow.Flow
import ox.flow.csv.CsvRecord.HeaderRecord
import ox.flow.csv.codec.{CsvRowDecoder, CsvRowEncoder}
import ox.flow.csv.compat.{ApacheParse, ApachePrint, RawCsvFormat}
import ox.flow.csv.format.typed.*

import scala.annotation.publicInBinary
import scala.deriving.Mirror.ProductOf

type Format[Repr] = ParserFormat[Repr] & PrinterFormat[Repr]

sealed trait ParserFormat[+Repr]:
  def parseData(data: Flow[String]): Repr

sealed trait PrinterFormat[-Repr]:
  def writeData(data: Repr): Flow[String]

@publicInBinary
private[csv] object Format:

  private final class OptionalHeaderFormat[T, S](
                                                  apacheFormat: RawCsvFormat,
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
  private final class NoHeaderFormat[T, S](
                                            apacheFormat: RawCsvFormat,
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
  private final class ForcedHeaderFormat[T, S](
                                                apacheFormat: RawCsvFormat,
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

  /** Constructs an [[OptionalHeaderFormat]] behind a widened result type, so the class itself stays
    * out of the bytecode of whoever the surrounding `inline def` expands into.
    */
  def optionalHeaderFormat[T, S](
                                  apacheFormat: RawCsvFormat,
                                  builder: TypedFormatBuilder[S],
                                  rowDecoder: CsvRowDecoder[T],
                                  rowEncoder: CsvRowEncoder[T]
  ): ParserFormat[Flow[HeaderRecord | T]] & PrinterFormat[Flow[HeaderRecord | T]] & HasBuilder[S] =
    new OptionalHeaderFormat[T, S](apacheFormat, builder, rowDecoder, rowEncoder)

  /** Constructs a [[NoHeaderFormat]]; see [[optionalHeaderFormat]] for why the result type is widened. */
  def noHeaderFormat[T, S](
                            apacheFormat: RawCsvFormat,
                            builder: TypedFormatBuilder[S],
                            rowDecoder: CsvRowDecoder[T],
                            rowEncoder: CsvRowEncoder[T]
  ): ParserFormat[Flow[T]] & PrinterFormat[Flow[T]] & HasBuilder[S] =
    new NoHeaderFormat[T, S](apacheFormat, builder, rowDecoder, rowEncoder)

  /** Constructs a [[ForcedHeaderFormat]]; see [[optionalHeaderFormat]] for why the result type is widened. */
  def forcedHeaderFormat[T, S](
                                apacheFormat: RawCsvFormat,
                                builder: TypedFormatBuilder[S],
                                rowEncoder: CsvRowEncoder[T]
  ): PrinterFormat[(HeaderRecord, Flow[T])] & HasBuilder[S] =
    new ForcedHeaderFormat[T, S](apacheFormat, builder, rowEncoder)

  /** The stream may carry a [[HeaderRecord]]: `Flow[HeaderRecord | T]`.
    *
    * A predefined format knows its own shape statically, so it calls this directly rather than going
    * through [[build]] — an `inline if` cannot reduce while `T` is still abstract in the format's own
    * signature, and the result would widen to the lub of all three shapes.
    */
  inline def optionalHeader[T, S](apacheFormat: RawCsvFormat, builder: TypedFormatBuilder[S])(using
                                                                                              mir: ProductOf[T]
  ): ParserFormat[Flow[HeaderRecord | T]] & PrinterFormat[Flow[HeaderRecord | T]] & HasBuilder[S] =
    inline val hasNullString = SettingsQuery.hasSetting[S, NullString[?]]
    inline val allowRaggedRows = !SettingsQuery.hasSetting[S, DisallowRaggedRows]
    optionalHeaderFormat[T, S](apacheFormat, builder, CsvRowDecoder[T](hasNullString, allowRaggedRows), CsvRowEncoder[T](hasNullString))

  /** No header at all: `Flow[T]`. */
  inline def noHeader[T, S](apacheFormat: RawCsvFormat, builder: TypedFormatBuilder[S])(using
                                                                                        mir: ProductOf[T]
  ): ParserFormat[Flow[T]] & PrinterFormat[Flow[T]] & HasBuilder[S] =
    inline val hasNullString = SettingsQuery.hasSetting[S, NullString[?]]
    inline val allowRaggedRows = !SettingsQuery.hasSetting[S, DisallowRaggedRows]
    noHeaderFormat[T, S](apacheFormat, builder, CsvRowDecoder[T](hasNullString, allowRaggedRows), CsvRowEncoder[T](hasNullString))

  /** Printer-only, header always emitted: `(HeaderRecord, Flow[T])`. */
  inline def forcedHeader[T, S](apacheFormat: RawCsvFormat, builder: TypedFormatBuilder[S])(using
                                                                                            mir: ProductOf[T]
  ): PrinterFormat[(HeaderRecord, Flow[T])] & HasBuilder[S] =
    inline val hasNullString = SettingsQuery.hasSetting[S, NullString[?]]
    forcedHeaderFormat[T, S](apacheFormat, builder, CsvRowEncoder[T](hasNullString))
