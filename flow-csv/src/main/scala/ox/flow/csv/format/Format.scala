package ox.flow.csv.format

import org.apache.commons.csv.CSVFormat as ApacheCSVFormat
import ox.flow.Flow
import ox.flow.csv.CsvRecord.HeaderRecord
import ox.channels.{BufferCapacity, ChannelClosedException}
import ox.flow.csv.ChannelReader
import ox.flow.csv.codec.internal.{CsvRowDecoder, CsvRowEncoder}
import ox.unsupervised

import java.io.StringWriter
import scala.jdk.CollectionConverters.*
import scala.annotation.publicInBinary
import ox.flow.csv.compat.ApacheCsvFormatCompat
import ox.flow.csv.format.internal.macros

import scala.deriving.Mirror.ProductOf

type Format[Repr] = ParserFormat[Repr] & PrinterFormat[Repr]

sealed trait ParserFormat[+Repr]:
  def parseData(data: Flow[String]): Repr

sealed trait PrinterFormat[-Repr]:
  def writeData(data: Repr): Flow[String]

object Format:
  object Predefined:

    type Rfc4180Builder = HasBuilder[HeaderModeSetting[HeaderMode.TryParseAsData]]
    /**
     * RFC 4180 compliant CSV format.
     * - Optional header
     * - Ragged rows allowed - requires capability to handle missing data for all [[T]] fields except first one (enforced at compiletime)
     * - Empty lines are interpreted as legitimate ragged row with value of first column being empty string
     * - Delimiter: comma, Quote: `"`, Record separator: `\r\n`
     */
    inline def Rfc4180[T](using mir: ProductOf[T]): Format[Flow[HeaderRecord | T]] & Rfc4180Builder =
      Format.optionalHeader[T, HeaderModeSetting[HeaderMode.TryParseAsData]](Rfc4180Apache, TypedFormatBuilder.unsafeFromSettings[HeaderModeSetting[HeaderMode.TryParseAsData]](Rfc4180))

    type DefaultBuilder = HasBuilder[IgnoreEmptyLines & HeaderModeSetting[HeaderMode.TryParseAsData]]
    /**
     * Standard CSV format (RFC 4180 + ignoreEmptyLines).
     * - Optional header
     * - Empty lines ignored silently
     * - Delimiter: comma, Quote: `"`, Record separator: `\r\n`
     */
    inline def Default[T](using mir: ProductOf[T]): Format[Flow[HeaderRecord | T]] & DefaultBuilder =
      Format.optionalHeader[T, IgnoreEmptyLines & HeaderModeSetting[HeaderMode.TryParseAsData]](DefaultApache, TypedFormatBuilder.unsafeFromSettings[IgnoreEmptyLines & HeaderModeSetting[HeaderMode.TryParseAsData]](Default))

    type Rfc4180StrictBuilder = HasBuilder[DisallowRaggedRows & HeaderModeSetting[HeaderMode.TryParseAsData]]
    /**
     * RFC 4180 with strict parsing - no empty column fallback for non-string columns, throws on ragged rows.
     * - Optional header
     * - Ragged rows fail at runtime (missing column on any field type, including String)
     * - Empty cells on non-String fields fail at runtime
     * - Delimiter: comma, Quote: `"`, Record separator: `\r\n`
     */
    inline def Rfc4180Strict[T](using mir: ProductOf[T]): Format[Flow[HeaderRecord | T]] & Rfc4180StrictBuilder =
      Format.optionalHeader[T, DisallowRaggedRows & HeaderModeSetting[HeaderMode.TryParseAsData]](Rfc4180Apache, TypedFormatBuilder.unsafeFromSettings[DisallowRaggedRows & HeaderModeSetting[HeaderMode.TryParseAsData]](Rfc4180))

    type ExcelBuilder = HasBuilder[HeaderModeSetting[HeaderMode.TryParseAsData]]
    /**
     * Microsoft Excel CSV format with null handling for empty cells.
     * - Optional header
     * - Allows missing column names, trailing data, lenient EOF
     * - Delimiter: comma, Quote: `"`, Record separator: `\r\n`
     */
    inline def Excel[T](using mir: ProductOf[T]): Format[Flow[HeaderRecord | T]] & ExcelBuilder =
      Format.optionalHeader[T, HeaderModeSetting[HeaderMode.TryParseAsData]](ExcelApache, TypedFormatBuilder.unsafeFromSettings[HeaderModeSetting[HeaderMode.TryParseAsData]](Excel))

    type ExcelStrictBuilder = HasBuilder[HeaderModeSetting[HeaderMode.TryParseAsData]]
    /**
     * Microsoft Excel CSV format with strict parsing - no empty column fallback for non-string columns, ParsingException is thrown when empty string is encountered for non-string column.
     * - Optional header
     * - Empty cells on non-String fields will fail at runtime
     * - Allows missing column names, trailing data, lenient EOF
     * - Delimiter: comma, Quote: `"`, Record separator: `\r\n`
     *
     */
    inline def ExcelStrict[T](using mir: ProductOf[T]): Format[Flow[HeaderRecord | T]] & ExcelStrictBuilder =
      Format.optionalHeader[T, HeaderModeSetting[HeaderMode.TryParseAsData]](ExcelApache, TypedFormatBuilder.unsafeFromSettings[HeaderModeSetting[HeaderMode.TryParseAsData]](Excel))

    type TdfBuilder = HasBuilder[IgnoreEmptyLines & HeaderModeSetting[HeaderMode.TryParseAsData]]
    /**
     * Tab-delimited format (TDF).
     * - Optional header
     * - Empty lines ignored
     * - Ignores surrounding spaces
     * - Delimiter: tab, Quote: `"`, Record separator: `\r\n`
     */
    inline def Tdf[T](using mir: ProductOf[T]): Format[Flow[HeaderRecord | T]] & TdfBuilder =
      Format.optionalHeader[T, IgnoreEmptyLines & HeaderModeSetting[HeaderMode.TryParseAsData]](TdfApache, TypedFormatBuilder.unsafeFromSettings[IgnoreEmptyLines & HeaderModeSetting[HeaderMode.TryParseAsData]](Tdf))

    type InformixUnloadBuilder = HasBuilder[IgnoreEmptyLines]
    /**
     * Informix UNLOAD format used by `UNLOAD TO file_name`.
     * - No header assumed
     * - Empty lines ignored
     * - Delimiter: pipe (`|`), Escape: `\`, Quote: `"`, Record separator: `\n`
     */
    inline def InformixUnload[T](using mir: ProductOf[T]): Format[Flow[T]] & InformixUnloadBuilder =
      Format.noHeader[T, IgnoreEmptyLines](InformixUnloadApache, TypedFormatBuilder.unsafeFromSettings[IgnoreEmptyLines](InformixUnload))

    type InformixUnloadCsvBuilder = HasBuilder[IgnoreEmptyLines]
    /**
     * Informix CSV UNLOAD format (escaping disabled).
     * - No header
     * - Empty lines ignored
     * - Delimiter: comma, Quote: `"`, Record separator: `\n`
     */
    inline def InformixUnloadCsv[T](using mir: ProductOf[T]): Format[Flow[T]] & InformixUnloadCsvBuilder =
      Format.noHeader[T, IgnoreEmptyLines](InformixUnloadCsvApache, TypedFormatBuilder.unsafeFromSettings[IgnoreEmptyLines](InformixUnloadCsv))

    type MongoDbCsvBuilder = HasBuilder[IgnoreEmptyLines & HeaderModeSetting[HeaderMode.Force]]
    /**
     * MongoDB CSV format used by `mongoexport` (printer only, parsing not supported).
     * - Header always expected/required
     * - Empty lines ignored
     * - Delimiter: comma, Escape: `"`, Quote: `"`, QuoteMode: MINIMAL
     */
    inline def MongoDbCsv[T](using mir: ProductOf[T]): PrinterFormat[(HeaderRecord, Flow[T])] & MongoDbCsvBuilder =
      Format.forcedHeader[T, IgnoreEmptyLines & HeaderModeSetting[HeaderMode.Force]](MongoDbCsvApache, TypedFormatBuilder.unsafeFromSettings[IgnoreEmptyLines & HeaderModeSetting[HeaderMode.Force]](MongoDbCsv))

    type MongoDbTsvBuilder = HasBuilder[IgnoreEmptyLines & HeaderModeSetting[HeaderMode.Force]]
    /**
     * MongoDB TSV format used by `mongoexport` (printer only, parsing not supported).
     * - Header always expected/required
     * - Empty lines ignored
     * - Delimiter: tab, Escape: `"`, Quote: `"`, QuoteMode: MINIMAL
     */
    inline def MongoDbTsv[T](using mir: ProductOf[T]): PrinterFormat[(HeaderRecord, Flow[T])] & MongoDbTsvBuilder =
      Format.forcedHeader[T, IgnoreEmptyLines & HeaderModeSetting[HeaderMode.Force]](MongoDbTsvApache, TypedFormatBuilder.unsafeFromSettings[IgnoreEmptyLines & HeaderModeSetting[HeaderMode.Force]](MongoDbTsv))

    type MySqlBuilder = HasBuilder[NullString["\\N"]]
    /**
     * MySQL format used by `SELECT INTO OUTFILE` and `LOAD DATA INFILE`.
     * - No header
     * - Null String: `\N` - [[T]] codecs need to be able to handle nulls
     * - Delimiter: tab, Escape: `\`, No quoting, Record separator: `\n`
     */
    inline def MySql[T](using mir: ProductOf[T]): Format[Flow[T]] & MySqlBuilder =
      Format.noHeader[T, NullString["\\N"]](MySqlApache, TypedFormatBuilder.unsafeFromSettings[NullString["\\N"]](MySql))

    type OracleBuilder = HasBuilder[NullString["\\N"]]
    /**
     * Oracle SQL*Loader format.
     * - No header
     * - Null String: `\N` - [[T]] codecs need to be able to handle nulls
     * - Delimiter: comma, Escape: `\`, Quote: `"`, Trims values, Record separator: system line separator
     */
    inline def Oracle[T](using mir: ProductOf[T]): Format[Flow[T]] & OracleBuilder =
      Format.noHeader[T, NullString["\\N"]](OracleApache, TypedFormatBuilder.unsafeFromSettings[NullString["\\N"]](Oracle))

    type PostgreSqlCsvBuilder = HasBuilder[NullString[""]]
    /**
     * Default PostgreSQL CSV format used by the COPY operation.
     * - No header
     * - Null String: "" - [[T]] codecs need to be able to handle nulls
     */
    inline def PostgreSqlCsv[T](using mir: ProductOf[T]): Format[Flow[T]] & PostgreSqlCsvBuilder =
      Format.noHeader[T, NullString[""]](PostgreSqlCsvApache, TypedFormatBuilder.unsafeFromSettings[NullString[""]](PostgreSqlCsv))

    type PostgreSqlTextBuilder = HasBuilder[NullString["\\N"]]
    /**
     * PostgreSQL text format used by the COPY operation.
     * - No header
     * - Null String: `\N` - [[T]] codecs need to be able to handle nulls
     * - Delimiter: tab, Escape: `\`, No quoting, Record separator: `\n`
     */
    inline def PostgreSqlText[T](using mir: ProductOf[T]): Format[Flow[T]] & PostgreSqlTextBuilder =
      Format.noHeader[T, NullString["\\N"]](PostgreSqlTextApache, TypedFormatBuilder.unsafeFromSettings[NullString["\\N"]](PostgreSqlText))

    /** Adapts a predefined Apache format settings to most permissive values
     * This is needed because otherwise apache parser would drop data of fail fast before ox could apply its own
     * format settings like [[HeaderMode]]
     */
    private def overrideSettingsForCompatibility(apacheFormat: RawCsvFormat, headerMode: HeaderMode): Settings =
      ApacheCsvFormatCompat.settingsOf(apacheFormat, headerMode)

    /** Cached [[Settings]] for inline constructors*/
    @publicInBinary private[csv] lazy val Default: Settings = overrideSettingsForCompatibility(apacheFormat = RawCsvFormat(ApacheCSVFormat.DEFAULT), headerMode = HeaderMode.TryParseAsData)
    @publicInBinary private[csv] lazy val Rfc4180: Settings = overrideSettingsForCompatibility(apacheFormat = RawCsvFormat(ApacheCSVFormat.RFC4180), headerMode = HeaderMode.TryParseAsData)
    @publicInBinary private[csv] lazy val Excel: Settings = overrideSettingsForCompatibility(apacheFormat = RawCsvFormat(ApacheCSVFormat.EXCEL), headerMode = HeaderMode.TryParseAsData)
    @publicInBinary private[csv] lazy val Tdf: Settings = overrideSettingsForCompatibility(apacheFormat = RawCsvFormat(ApacheCSVFormat.TDF), headerMode = HeaderMode.TryParseAsData)
    @publicInBinary private[csv] lazy val InformixUnload: Settings = overrideSettingsForCompatibility(apacheFormat = RawCsvFormat(ApacheCSVFormat.INFORMIX_UNLOAD), headerMode = HeaderMode.TryParseAsData)
    @publicInBinary private[csv] lazy val InformixUnloadCsv: Settings = overrideSettingsForCompatibility(apacheFormat = RawCsvFormat(ApacheCSVFormat.INFORMIX_UNLOAD_CSV), headerMode = HeaderMode.TryParseAsData)
    @publicInBinary private[csv] lazy val MongoDbCsv: Settings = overrideSettingsForCompatibility(apacheFormat = RawCsvFormat(ApacheCSVFormat.MONGODB_CSV), headerMode = HeaderMode.TryParseAsData)
    @publicInBinary private[csv] lazy val MongoDbTsv: Settings = overrideSettingsForCompatibility(apacheFormat = RawCsvFormat(ApacheCSVFormat.MONGODB_TSV), headerMode = HeaderMode.TryParseAsData)
    @publicInBinary private[csv] lazy val MySql: Settings = overrideSettingsForCompatibility(apacheFormat = RawCsvFormat(ApacheCSVFormat.MYSQL), headerMode = HeaderMode.TryParseAsData)
    @publicInBinary private[csv] lazy val Oracle: Settings = overrideSettingsForCompatibility(apacheFormat = RawCsvFormat(ApacheCSVFormat.ORACLE), headerMode = HeaderMode.TryParseAsData)
    @publicInBinary private[csv] lazy val PostgreSqlCsv: Settings = overrideSettingsForCompatibility(apacheFormat = RawCsvFormat(ApacheCSVFormat.POSTGRESQL_CSV), headerMode = HeaderMode.TryParseAsData)
    @publicInBinary private[csv] lazy val PostgreSqlText: Settings = overrideSettingsForCompatibility(apacheFormat = RawCsvFormat(ApacheCSVFormat.POSTGRESQL_TEXT), headerMode = HeaderMode.TryParseAsData)

    /** Cached [[RawCsvFormat]] for inline constructors*/
    @publicInBinary private[csv] lazy val DefaultApache: RawCsvFormat = ApacheCsvFormatCompat.toApache(Default)
    @publicInBinary private[csv] lazy val Rfc4180Apache: RawCsvFormat = ApacheCsvFormatCompat.toApache(Rfc4180)
    @publicInBinary private[csv] lazy val ExcelApache: RawCsvFormat = ApacheCsvFormatCompat.toApache(Excel)
    @publicInBinary private[csv] lazy val TdfApache: RawCsvFormat = ApacheCsvFormatCompat.toApache(Tdf)
    @publicInBinary private[csv] lazy val InformixUnloadApache: RawCsvFormat = ApacheCsvFormatCompat.toApache(InformixUnload)
    @publicInBinary private[csv] lazy val InformixUnloadCsvApache: RawCsvFormat = ApacheCsvFormatCompat.toApache(InformixUnloadCsv)
    @publicInBinary private[csv] lazy val MongoDbCsvApache: RawCsvFormat = ApacheCsvFormatCompat.toApache(MongoDbCsv)
    @publicInBinary private[csv] lazy val MongoDbTsvApache: RawCsvFormat = ApacheCsvFormatCompat.toApache(MongoDbTsv)
    @publicInBinary private[csv] lazy val MySqlApache: RawCsvFormat = ApacheCsvFormatCompat.toApache(MySql)
    @publicInBinary private[csv] lazy val OracleApache: RawCsvFormat = ApacheCsvFormatCompat.toApache(Oracle)
    @publicInBinary private[csv] lazy val PostgreSqlCsvApache: RawCsvFormat = ApacheCsvFormatCompat.toApache(PostgreSqlCsv)
    @publicInBinary private[csv] lazy val PostgreSqlTextApache: RawCsvFormat = ApacheCsvFormatCompat.toApache(PostgreSqlText)
  end Predefined
  
  private def parseRows(format: RawCsvFormat, data: Flow[String])(using BufferCapacity): Flow[Array[String | Null]] =
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
  
  private def printRows(format: RawCsvFormat, data: Flow[Array[String | Null]]): Flow[String] =
    val apacheFormat = format.underlying
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

  private final class OptionalHeaderFormat[T, S](
                                                  apacheFormat: RawCsvFormat,
                                                  val builder: TypedFormatBuilder[S],
                                                  rowDecoder: CsvRowDecoder[T],
                                                  rowEncoder: CsvRowEncoder[T]
  ) extends ParserFormat[Flow[HeaderRecord | T]]
      with PrinterFormat[Flow[HeaderRecord | T]]
      with HasBuilder[S]:

    def parseData(data: Flow[String]): Flow[HeaderRecord | T] =
      parseRows(apacheFormat, data).map(rowDecoder.decodeRow)

    def writeData(data: Flow[HeaderRecord | T]): Flow[String] =
      printRows(
        apacheFormat,
        data.map:
          case h: HeaderRecord => h.names.toArray[String | Null]
          case t               => rowEncoder.encodeRow(t.asInstanceOf[T])
      )

  private final class NoHeaderFormat[T, S](
                                            apacheFormat: RawCsvFormat,
                                            val builder: TypedFormatBuilder[S],
                                            rowDecoder: CsvRowDecoder[T],
                                            rowEncoder: CsvRowEncoder[T]
  ) extends ParserFormat[Flow[T]]
      with PrinterFormat[Flow[T]]
      with HasBuilder[S]:

    def parseData(data: Flow[String]): Flow[T] =
      parseRows(apacheFormat, data).map(rowDecoder.decodeRow)

    def writeData(data: Flow[T]): Flow[String] =
      printRows(apacheFormat, data.map(rowEncoder.encodeRow))

  private final class ForcedHeaderFormat[T, S](
                                                apacheFormat: RawCsvFormat,
                                                val builder: TypedFormatBuilder[S],
                                                rowEncoder: CsvRowEncoder[T]
  ) extends PrinterFormat[(HeaderRecord, Flow[T])], HasBuilder[S]:

    def writeData(data: (HeaderRecord, Flow[T])): Flow[String] =
      val (header, rows) = data
      printRows(
        apacheFormat,
        Flow.fromValues(header.names.toArray[String | Null]).concat(rows.map(rowEncoder.encodeRow))
      )


  @publicInBinary
  private [csv] def optionalHeaderFormat[T, S](
                                  apacheFormat: RawCsvFormat,
                                  builder: TypedFormatBuilder[S],
                                  rowDecoder: CsvRowDecoder[T],
                                  rowEncoder: CsvRowEncoder[T]
  ): ParserFormat[Flow[HeaderRecord | T]] & PrinterFormat[Flow[HeaderRecord | T]] & HasBuilder[S] =
    new OptionalHeaderFormat[T, S](apacheFormat, builder, rowDecoder, rowEncoder)


  @publicInBinary
  private [csv] def noHeaderFormat[T, S](
                            apacheFormat: RawCsvFormat,
                            builder: TypedFormatBuilder[S],
                            rowDecoder: CsvRowDecoder[T],
                            rowEncoder: CsvRowEncoder[T]
  ): ParserFormat[Flow[T]] & PrinterFormat[Flow[T]] & HasBuilder[S] =
    new NoHeaderFormat[T, S](apacheFormat, builder, rowDecoder, rowEncoder)


  @publicInBinary
  private [csv] def forcedHeaderFormat[T, S](
                                apacheFormat: RawCsvFormat,
                                builder: TypedFormatBuilder[S],
                                rowEncoder: CsvRowEncoder[T]
  ): PrinterFormat[(HeaderRecord, Flow[T])] & HasBuilder[S] =
    new ForcedHeaderFormat[T, S](apacheFormat, builder, rowEncoder)

  
  private [csv] inline def optionalHeader[T, S](apacheFormat: RawCsvFormat, builder: TypedFormatBuilder[S])(using
                                                                                              mir: ProductOf[T]
  ): ParserFormat[Flow[HeaderRecord | T]] & PrinterFormat[Flow[HeaderRecord | T]] & HasBuilder[S] =
    inline val hasNullString = macros.isSubtypeOf[S, NullString[?]]
    inline val allowRaggedRows = !macros.isSubtypeOf[S, DisallowRaggedRows]
    optionalHeaderFormat[T, S](apacheFormat, builder, CsvRowDecoder[T](hasNullString, allowRaggedRows), CsvRowEncoder[T](hasNullString))


  private [csv] inline def noHeader[T, S](apacheFormat: RawCsvFormat, builder: TypedFormatBuilder[S])(using
                                                                                        mir: ProductOf[T]
  ): ParserFormat[Flow[T]] & PrinterFormat[Flow[T]] & HasBuilder[S] =
    inline val hasNullString = macros.isSubtypeOf[S, NullString[?]]
    inline val allowRaggedRows = !macros.isSubtypeOf[S, DisallowRaggedRows]
    noHeaderFormat[T, S](apacheFormat, builder, CsvRowDecoder[T](hasNullString, allowRaggedRows), CsvRowEncoder[T](hasNullString))

  private [csv] inline def forcedHeader[T, S](apacheFormat: RawCsvFormat, builder: TypedFormatBuilder[S])(using
                                                                                            mir: ProductOf[T]
  ): PrinterFormat[(HeaderRecord, Flow[T])] & HasBuilder[S] =
    inline val hasNullString = macros.isSubtypeOf[S, NullString[?]]
    forcedHeaderFormat[T, S](apacheFormat, builder, CsvRowEncoder[T](hasNullString))

