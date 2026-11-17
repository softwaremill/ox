package ox.flow.csv.format

import org.apache.commons.csv.CSVFormat.Builder as ApacheCSVBuilder
import org.apache.commons.csv.DuplicateHeaderMode
import org.apache.commons.csv.CSVFormat as ApacheCSVFormat
import ox.flow.Flow
import ox.flow.csv.CsvRecord.HeaderRecord
import ox.channels.{BufferCapacity, ChannelClosedException}
import ox.flow.csv.codec.internal.{CsvRowDecoder, CsvRowEncoder}
import ox.unsupervised

import java.io.StringWriter
import scala.jdk.CollectionConverters.*
import scala.annotation.publicInBinary
import ox.flow.csv.format.internal.macros
import ox.flow.csv.internal.ChannelReader

import scala.deriving.Mirror.ProductOf

type Format[Repr] = ParserFormat[Repr] & PrinterFormat[Repr]

sealed trait ParserFormat[+Repr]:
  def parseData(data: Flow[String]): Repr

sealed trait PrinterFormat[-Repr]:
  def writeData(data: Repr): Flow[String]

object Format:

  type Rfc4180Builder = HasBuilder[HeaderModeSetting[HeaderMode.TryParseAsData]]
  /**
   * RFC 4180 compliant CSV format.
   * - Optional header
   * - Ragged rows allowed - requires capability to handle missing data for all [[T]] fields except first one (enforced at compiletime)
   * - Empty lines are interpreted as legitimate ragged row with value of first column being empty string
   * - Delimiter: comma, Quote: `"`, Record separator: `\r\n`
   */
  inline def Rfc4180[T](using mir: ProductOf[T]): Format[Flow[HeaderRecord | T]] & Rfc4180Builder =
    Format.optionalHeader[T, HeaderModeSetting[HeaderMode.TryParseAsData]](Rfc4180Apache, Builder.Rfc4180)

  type DefaultBuilder = HasBuilder[IgnoreEmptyLines & HeaderModeSetting[HeaderMode.TryParseAsData]]
  /**
   * Standard CSV format (RFC 4180 + ignoreEmptyLines).
   * - Optional header
   * - Empty lines ignored silently
   * - Delimiter: comma, Quote: `"`, Record separator: `\r\n`
   */
  inline def Default[T](using mir: ProductOf[T]): Format[Flow[HeaderRecord | T]] & DefaultBuilder =
    Format.optionalHeader[T, IgnoreEmptyLines & HeaderModeSetting[HeaderMode.TryParseAsData]](DefaultApache, Builder.Default)

  type Rfc4180StrictBuilder = HasBuilder[DisallowRaggedRows & HeaderModeSetting[HeaderMode.TryParseAsData]]
  /**
   * RFC 4180 with strict parsing - no empty column fallback for non-string columns, throws on ragged rows.
   * - Optional header
   * - Ragged rows fail at runtime (missing column on any field type, including String)
   * - Empty cells on non-String fields fail at runtime
   * - Delimiter: comma, Quote: `"`, Record separator: `\r\n`
   */
  inline def Rfc4180Strict[T](using mir: ProductOf[T]): Format[Flow[HeaderRecord | T]] & Rfc4180StrictBuilder =
    Format.optionalHeader[T, DisallowRaggedRows & HeaderModeSetting[HeaderMode.TryParseAsData]](Rfc4180Apache, Builder.Rfc4180Strict)

  type ExcelBuilder = HasBuilder[HeaderModeSetting[HeaderMode.TryParseAsData]]
  /**
   * Microsoft Excel CSV format with null handling for empty cells.
   * - Optional header
   * - Allows missing column names, trailing data, lenient EOF
   * - Delimiter: comma, Quote: `"`, Record separator: `\r\n`
   */
  inline def Excel[T](using mir: ProductOf[T]): Format[Flow[HeaderRecord | T]] & ExcelBuilder =
    Format.optionalHeader[T, HeaderModeSetting[HeaderMode.TryParseAsData]](ExcelApache, Builder.Excel)

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
    Format.optionalHeader[T, HeaderModeSetting[HeaderMode.TryParseAsData]](ExcelApache, Builder.ExcelStrict)

  type TdfBuilder = HasBuilder[IgnoreEmptyLines & HeaderModeSetting[HeaderMode.TryParseAsData]]
  /**
   * Tab-delimited format (TDF).
   * - Optional header
   * - Empty lines ignored
   * - Ignores surrounding spaces
   * - Delimiter: tab, Quote: `"`, Record separator: `\r\n`
   */
  inline def Tdf[T](using mir: ProductOf[T]): Format[Flow[HeaderRecord | T]] & TdfBuilder =
    Format.optionalHeader[T, IgnoreEmptyLines & HeaderModeSetting[HeaderMode.TryParseAsData]](TdfApache, Builder.Tdf)

  type InformixUnloadBuilder = HasBuilder[IgnoreEmptyLines]
  /**
   * Informix UNLOAD format used by `UNLOAD TO file_name`.
   * - No header assumed
   * - Empty lines ignored
   * - Delimiter: pipe (`|`), Escape: `\`, Quote: `"`, Record separator: `\n`
   */
  inline def InformixUnload[T](using mir: ProductOf[T]): Format[Flow[T]] & InformixUnloadBuilder =
    Format.noHeader[T, IgnoreEmptyLines](InformixUnloadApache, Builder.InformixUnload)

  type InformixUnloadCsvBuilder = HasBuilder[IgnoreEmptyLines]
  /**
   * Informix CSV UNLOAD format (escaping disabled).
   * - No header
   * - Empty lines ignored
   * - Delimiter: comma, Quote: `"`, Record separator: `\n`
   */
  inline def InformixUnloadCsv[T](using mir: ProductOf[T]): Format[Flow[T]] & InformixUnloadCsvBuilder =
    Format.noHeader[T, IgnoreEmptyLines](InformixUnloadCsvApache, Builder.InformixUnloadCsv)

  type MongoDbCsvBuilder = HasBuilder[IgnoreEmptyLines & HeaderModeSetting[HeaderMode.Force]]
  /**
   * MongoDB CSV format used by `mongoexport` (printer only, parsing not supported).
   * - Header always expected/required
   * - Empty lines ignored
   * - Delimiter: comma, Escape: `"`, Quote: `"`, QuoteMode: MINIMAL
   */
  inline def MongoDbCsv[T](using mir: ProductOf[T]): PrinterFormat[(HeaderRecord, Flow[T])] & MongoDbCsvBuilder =
    Format.forcedHeader[T, IgnoreEmptyLines & HeaderModeSetting[HeaderMode.Force]](MongoDbCsvApache, Builder.MongoDbCsv)

  type MongoDbTsvBuilder = HasBuilder[IgnoreEmptyLines & HeaderModeSetting[HeaderMode.Force]]
  /**
   * MongoDB TSV format used by `mongoexport` (printer only, parsing not supported).
   * - Header always expected/required
   * - Empty lines ignored
   * - Delimiter: tab, Escape: `"`, Quote: `"`, QuoteMode: MINIMAL
   */
  inline def MongoDbTsv[T](using mir: ProductOf[T]): PrinterFormat[(HeaderRecord, Flow[T])] & MongoDbTsvBuilder =
    Format.forcedHeader[T, IgnoreEmptyLines & HeaderModeSetting[HeaderMode.Force]](MongoDbTsvApache, Builder.MongoDbTsv)

  type MySqlBuilder = HasBuilder[NullString["\\N"]]
  /**
   * MySQL format used by `SELECT INTO OUTFILE` and `LOAD DATA INFILE`.
   * - No header
   * - Null String: `\N` - [[T]] codecs need to be able to handle nulls
   * - Delimiter: tab, Escape: `\`, No quoting, Record separator: `\n`
   */
  inline def MySql[T](using mir: ProductOf[T]): Format[Flow[T]] & MySqlBuilder =
    Format.noHeader[T, NullString["\\N"]](MySqlApache, Builder.MySql)

  type OracleBuilder = HasBuilder[NullString["\\N"]]
  /**
   * Oracle SQL*Loader format.
   * - No header
   * - Null String: `\N` - [[T]] codecs need to be able to handle nulls
   * - Delimiter: comma, Escape: `\`, Quote: `"`, Trims values, Record separator: system line separator
   */
  inline def Oracle[T](using mir: ProductOf[T]): Format[Flow[T]] & OracleBuilder =
    Format.noHeader[T, NullString["\\N"]](OracleApache, Builder.Oracle)

  type PostgreSqlCsvBuilder = HasBuilder[NullString[""]]
  /**
   * Default PostgreSQL CSV format used by the COPY operation.
   * - No header
   * - Null String: "" - [[T]] codecs need to be able to handle nulls
   */
  inline def PostgreSqlCsv[T](using mir: ProductOf[T]): Format[Flow[T]] & PostgreSqlCsvBuilder =
    Format.noHeader[T, NullString[""]](PostgreSqlCsvApache, Builder.PostgreSqlCsv)

  type PostgreSqlTextBuilder = HasBuilder[NullString["\\N"]]
  /**
   * PostgreSQL text format used by the COPY operation.
   * - No header
   * - Null String: `\N` - [[T]] codecs need to be able to handle nulls
   * - Delimiter: tab, Escape: `\`, No quoting, Record separator: `\n`
   */
  inline def PostgreSqlText[T](using mir: ProductOf[T]): Format[Flow[T]] & PostgreSqlTextBuilder =
    Format.noHeader[T, NullString["\\N"]](PostgreSqlTextApache, Builder.PostgreSqlText)
  
  sealed trait Builder[S]:

    /** The dialect accumulated so far, as plain data. A DSL step copies this and widens `S`; the Apache
     * format is materialised once, by [[build]], since constructing it is what validates the settings. */
    @publicInBinary
    private [csv] def settings: Builder.Settings

  object Builder:

    extension [S](self: Builder[S])
      /** Builds the format for `T` from the settings `S` carries.
       *
       * `S` decides both the stream shape and which typeclasses each field must provide:
       *   - [[NullString]] present: cells can arrive as `null`, so a [[ox.flow.csv.codec.NullFallback]] is required
       *   - [[DisallowRaggedRows]] absent: trailing cells can be missing, so a
       *     [[ox.flow.csv.codec.MissingDataFallback]] is required
       *   - [[HeaderModeSetting]] present: the stream may carry a [[ox.flow.csv.CsvRecord.HeaderRecord]]
       *
       * `transparent` so the caller gets the precise format type back. The reduction needs `T` to be
       * concrete, which it is at a call site; the predefined formats instead call the shape-specific
       * builders in [[Format]] directly, since they know their shape statically.
       */
      transparent inline def build[T](using mir: ProductOf[T]): Any = //builds PrinterFormat or ParserFormat or Both with HasBuilder
        // building the Apache format is what validates the settings
        val format = RawCsvFormat.fromSettingsValidated(self.settings)
        inline if macros.isSubtypeOf[S, HeaderModeSetting[HeaderMode.Force]] then
        forcedHeader[T, S](format, self)
        else inline if macros.isSubtypeOf[S, HeaderModeSetting[?]] then
        optionalHeader[T, S](format, self)
        else noHeader[T, S](format, self)

    /** A [[Builder]] per predefined dialect, to start a custom format from.
     *
     * Unlike [[Format]], these carry only settings: nothing is validated until [[build]] is
     * called, so a dialect can be adjusted through states its own settings would reject on their own.
     */
    lazy val Rfc4180: Builder[HeaderModeSetting[HeaderMode.TryParseAsData]] = unsafeFromSettings(Settings.fromApache(ApacheCSVFormat.RFC4180, HeaderMode.TryParseAsData))
    lazy val Default: Builder[IgnoreEmptyLines & HeaderModeSetting[HeaderMode.TryParseAsData]] = unsafeFromSettings(Settings.fromApache(ApacheCSVFormat.DEFAULT, HeaderMode.TryParseAsData))
    lazy val Rfc4180Strict: Builder[DisallowRaggedRows & HeaderModeSetting[HeaderMode.TryParseAsData]] = unsafeFromSettings(Settings.fromApache(ApacheCSVFormat.RFC4180, HeaderMode.TryParseAsData))
    lazy val Excel: Builder[HeaderModeSetting[HeaderMode.TryParseAsData]] = unsafeFromSettings(Settings.fromApache(ApacheCSVFormat.EXCEL, HeaderMode.TryParseAsData))
    lazy val ExcelStrict: Builder[HeaderModeSetting[HeaderMode.TryParseAsData]] = unsafeFromSettings(Settings.fromApache(ApacheCSVFormat.EXCEL, HeaderMode.TryParseAsData))
    lazy val Tdf: Builder[IgnoreEmptyLines & HeaderModeSetting[HeaderMode.TryParseAsData]] = unsafeFromSettings(Settings.fromApache(ApacheCSVFormat.TDF, HeaderMode.TryParseAsData))
    lazy val InformixUnload: Builder[IgnoreEmptyLines] = unsafeFromSettings(Settings.fromApache(ApacheCSVFormat.INFORMIX_UNLOAD, HeaderMode.TryParseAsData))
    lazy val InformixUnloadCsv: Builder[IgnoreEmptyLines] = unsafeFromSettings(Settings.fromApache(ApacheCSVFormat.INFORMIX_UNLOAD_CSV, HeaderMode.TryParseAsData))
    lazy val MongoDbCsv: Builder[IgnoreEmptyLines & HeaderModeSetting[HeaderMode.Force]] = unsafeFromSettings(Settings.fromApache(ApacheCSVFormat.MONGODB_CSV, HeaderMode.TryParseAsData))
    lazy val MongoDbTsv: Builder[IgnoreEmptyLines & HeaderModeSetting[HeaderMode.Force]] = unsafeFromSettings(Settings.fromApache(ApacheCSVFormat.MONGODB_TSV, HeaderMode.TryParseAsData))
    lazy val MySql: Builder[NullString["\\N"]] = unsafeFromSettings(Settings.fromApache(ApacheCSVFormat.MYSQL, HeaderMode.TryParseAsData))
    lazy val Oracle: Builder[NullString["\\N"]] = unsafeFromSettings(Settings.fromApache(ApacheCSVFormat.ORACLE, HeaderMode.TryParseAsData))
    lazy val PostgreSqlCsv: Builder[NullString[""]] = unsafeFromSettings(Settings.fromApache(ApacheCSVFormat.POSTGRESQL_CSV, HeaderMode.TryParseAsData))
    lazy val PostgreSqlText: Builder[NullString["\\N"]] = unsafeFromSettings(Settings.fromApache(ApacheCSVFormat.POSTGRESQL_TEXT, HeaderMode.TryParseAsData))

    private final class Instance[S](@publicInBinary val settings: Settings) extends Builder[S]

    @publicInBinary
    private [csv] def unsafeFromSettings[S](settings: Settings) :Builder[S] = new Instance(settings)

    /** The settings a [[CsvFormat]] is described by: plain, immutable, side effect free data that can be copied and passed around freely.
     *
     * Start from [[Settings.Default]] (or any format's own [[CsvFormat.settings]]) and `copy` the settings that differ:
     * {{{
     * CsvFormat(Settings.Default.copy(delimiter = ";", trim = true))
     * }}}
     * @note Serialization is not supported
     */
    private [csv] final case class Settings private[csv](
                                                          headerMode: HeaderMode,
                                                          commentMarker: Option[Char],
                                                          delimiter: String,
                                                          escape: Option[Char],
                                                          ignoreSurroundingSpaces: Boolean,
                                                          lenientEof: Boolean,
                                                          nullString: Option[String],
                                                          quote: Option[Char],
                                                          quoteMode: Option[QuoteMode],
                                                          recordSeparator: String,
                                                          trailingData: Boolean,
                                                          trailingDelimiter: Boolean,
                                                          trim: Boolean
                                                        )
    private[csv] object Settings:
      /** Reads the settings out of a predefined Apache format.
       *
       * No compatibility override is applied first: the settings it pins (header handling, auto-flush, max rows)
       * are not among the ones read here, so relaxing them could not change the result.
       */
      def fromApache(format: ApacheCSVFormat, headerMode: HeaderMode): Settings =
        Settings(
          headerMode = headerMode,
          commentMarker = Option(format.getCommentMarker).map(_.charValue),
          delimiter = format.getDelimiterString,
          escape = Option(format.getEscapeCharacter).map(_.charValue),
          ignoreSurroundingSpaces = format.getIgnoreSurroundingSpaces,
          lenientEof = format.getLenientEof,
          nullString = Option(format.getNullString),
          quote = Option(format.getQuoteCharacter).map(_.charValue),
          quoteMode = Option(format.getQuoteMode).map(QuoteMode.fromApache),
          recordSeparator = format.getRecordSeparator,
          trailingData = format.getTrailingData,
          trailingDelimiter = format.getTrailingDelimiter,
          trim = format.getTrim
        )

  end Builder

  /** Cached [[RawCsvFormat]] for inline constructors*/
  @publicInBinary private[csv] lazy val DefaultApache: RawCsvFormat = RawCsvFormat.fromSettingsValidated(Builder.Default.settings)
  @publicInBinary private[csv] lazy val Rfc4180Apache: RawCsvFormat = RawCsvFormat.fromSettingsValidated(Builder.Rfc4180.settings)
  @publicInBinary private[csv] lazy val ExcelApache: RawCsvFormat = RawCsvFormat.fromSettingsValidated(Builder.Excel.settings)
  @publicInBinary private[csv] lazy val TdfApache: RawCsvFormat = RawCsvFormat.fromSettingsValidated(Builder.Tdf.settings)
  @publicInBinary private[csv] lazy val InformixUnloadApache: RawCsvFormat = RawCsvFormat.fromSettingsValidated(Builder.InformixUnload.settings)
  @publicInBinary private[csv] lazy val InformixUnloadCsvApache: RawCsvFormat = RawCsvFormat.fromSettingsValidated(Builder.InformixUnloadCsv.settings)
  @publicInBinary private[csv] lazy val MongoDbCsvApache: RawCsvFormat = RawCsvFormat.fromSettingsValidated(Builder.MongoDbCsv.settings)
  @publicInBinary private[csv] lazy val MongoDbTsvApache: RawCsvFormat = RawCsvFormat.fromSettingsValidated(Builder.MongoDbTsv.settings)
  @publicInBinary private[csv] lazy val MySqlApache: RawCsvFormat = RawCsvFormat.fromSettingsValidated(Builder.MySql.settings)
  @publicInBinary private[csv] lazy val OracleApache: RawCsvFormat = RawCsvFormat.fromSettingsValidated(Builder.Oracle.settings)
  @publicInBinary private[csv] lazy val PostgreSqlCsvApache: RawCsvFormat = RawCsvFormat.fromSettingsValidated(Builder.PostgreSqlCsv.settings)
  @publicInBinary private[csv] lazy val PostgreSqlTextApache: RawCsvFormat = RawCsvFormat.fromSettingsValidated(Builder.PostgreSqlText.settings)

  private def parseRows(format: RawCsvFormat, data: Flow[String])(using BufferCapacity): Flow[Array[String | Null]] =
    val underlying = format.underlying
    Flow.usingEmit: emit =>
      // the body of `unsupervised` runs on the calling thread, so `emit` is called from it as well (as required),
      // while the flow producing the text is run in a fork by `runToChannel`
      unsupervised:
        try
          val parser = underlying.parse(ChannelReader(data.runToChannel()))
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
    val underlying = format.underlying
    Flow.usingEmit: emit =>
      val buffer = StringWriter()
      val printer = underlying.print(buffer)
      try
        data.runToEmit: row =>
          printer.printRecord(row*)
          printer.flush()
          emit(buffer.toString)
          buffer.getBuffer.setLength(0)
      finally printer.close()

  sealed trait HasBuilder[S]:
    def builder: Builder[S]

  private final class OptionalHeaderFormat[T, S](
                                                  format: RawCsvFormat,
                                                  val builder: Builder[S],
                                                  rowDecoder: CsvRowDecoder[T],
                                                  rowEncoder: CsvRowEncoder[T]
  ) extends ParserFormat[Flow[HeaderRecord | T]]
      with PrinterFormat[Flow[HeaderRecord | T]]
      with HasBuilder[S]:

    def parseData(data: Flow[String]): Flow[HeaderRecord | T] =
      parseRows(format, data).map(rowDecoder.decodeRow)

    def writeData(data: Flow[HeaderRecord | T]): Flow[String] =
      printRows(
        format,
        data.map:
          case h: HeaderRecord => h.names.toArray[String | Null]
          case t               => rowEncoder.encodeRow(t.asInstanceOf[T])
      )

  private final class NoHeaderFormat[T, S](
                                            format: RawCsvFormat,
                                            val builder: Builder[S],
                                            rowDecoder: CsvRowDecoder[T],
                                            rowEncoder: CsvRowEncoder[T]
  ) extends ParserFormat[Flow[T]]
      with PrinterFormat[Flow[T]]
      with HasBuilder[S]:

    def parseData(data: Flow[String]): Flow[T] =
      parseRows(format, data).map(rowDecoder.decodeRow)

    def writeData(data: Flow[T]): Flow[String] =
      printRows(format, data.map(rowEncoder.encodeRow))

  private final class ForcedHeaderFormat[T, S](
                                                format: RawCsvFormat,
                                                val builder: Builder[S],
                                                rowEncoder: CsvRowEncoder[T]
  ) extends PrinterFormat[(HeaderRecord, Flow[T])], HasBuilder[S]:

    def writeData(data: (HeaderRecord, Flow[T])): Flow[String] =
      val (header, rows) = data
      printRows(
        format,
        Flow.fromValues(header.names.toArray[String | Null]).concat(rows.map(rowEncoder.encodeRow))
      )


  @publicInBinary
  private [csv] def optionalHeaderFormat[T, S](
                                                format: RawCsvFormat,
                                                builder: Builder[S],
                                                rowDecoder: CsvRowDecoder[T],
                                                rowEncoder: CsvRowEncoder[T]
  ): ParserFormat[Flow[HeaderRecord | T]] & PrinterFormat[Flow[HeaderRecord | T]] & HasBuilder[S] =
    new OptionalHeaderFormat[T, S](format, builder, rowDecoder, rowEncoder)


  @publicInBinary
  private [csv] def noHeaderFormat[T, S](
                                          format: RawCsvFormat,
                                          builder: Builder[S],
                                          rowDecoder: CsvRowDecoder[T],
                                          rowEncoder: CsvRowEncoder[T]
  ): ParserFormat[Flow[T]] & PrinterFormat[Flow[T]] & HasBuilder[S] =
    new NoHeaderFormat[T, S](format, builder, rowDecoder, rowEncoder)


  @publicInBinary
  private [csv] def forcedHeaderFormat[T, S](
                                              format: RawCsvFormat,
                                              builder: Builder[S],
                                              rowEncoder: CsvRowEncoder[T]
  ): PrinterFormat[(HeaderRecord, Flow[T])] & HasBuilder[S] =
    new ForcedHeaderFormat[T, S](format, builder, rowEncoder)

  
  private [csv] inline def optionalHeader[T, S](format: RawCsvFormat, builder: Builder[S])(using
                                                                                                       mir: ProductOf[T]
  ): ParserFormat[Flow[HeaderRecord | T]] & PrinterFormat[Flow[HeaderRecord | T]] & HasBuilder[S] =
    inline val hasNullString = macros.isSubtypeOf[S, NullString[?]]
    inline val allowRaggedRows = !macros.isSubtypeOf[S, DisallowRaggedRows]
    optionalHeaderFormat[T, S](format, builder, CsvRowDecoder[T](hasNullString, allowRaggedRows), CsvRowEncoder[T](hasNullString))


  private [csv] inline def noHeader[T, S](format: RawCsvFormat, builder: Builder[S])(using
                                                                                                 mir: ProductOf[T]
  ): ParserFormat[Flow[T]] & PrinterFormat[Flow[T]] & HasBuilder[S] =
    inline val hasNullString = macros.isSubtypeOf[S, NullString[?]]
    inline val allowRaggedRows = !macros.isSubtypeOf[S, DisallowRaggedRows]
    noHeaderFormat[T, S](format, builder, CsvRowDecoder[T](hasNullString, allowRaggedRows), CsvRowEncoder[T](hasNullString))

  private [csv] inline def forcedHeader[T, S](format: RawCsvFormat, builder: Builder[S])(using
                                                                                                     mir: ProductOf[T]
  ): PrinterFormat[(HeaderRecord, Flow[T])] & HasBuilder[S] =
    inline val hasNullString = macros.isSubtypeOf[S, NullString[?]]
    forcedHeaderFormat[T, S](format, builder, CsvRowEncoder[T](hasNullString))

  /**
   * [[ApacheCSVFormat]] wrapper not to expose it to user code (even inlined)
    */
  private[csv] final class RawCsvFormat(private[csv] val underlying: ApacheCSVFormat)

  private[csv] object RawCsvFormat:

    /** Applies the settings to a newly created [[ApacheCSVBuilder]], which validates them as the format is constructed.
     * Adapts apache settings to be compatible with ox*/
    @throws[IllegalArgumentException]
    private[csv] def fromSettingsValidated(settings: Builder.Settings): RawCsvFormat =
      val b = ApacheCSVBuilder.create()
      b.setCommentMarker(settings.commentMarker.map(Character.valueOf).orNull)
      b.setDelimiter(settings.delimiter)
      b.setEscape(settings.escape.map(Character.valueOf).orNull)
      b.setIgnoreSurroundingSpaces(settings.ignoreSurroundingSpaces)
      b.setLenientEof(settings.lenientEof)
      b.setNullString(settings.nullString.orNull)
      b.setQuote(settings.quote.map(Character.valueOf).orNull)
      b.setQuoteMode(settings.quoteMode.map(_.toApache).orNull)
      b.setRecordSeparator(settings.recordSeparator)
      b.setTrailingData(settings.trailingData)
      b.setTrailingDelimiter(settings.trailingDelimiter)
      b.setTrim(settings.trim)

      RawCsvFormat(overrideSettingsForCompatibility(b).get)

    /** Brings every Apache setting that is not part of our contract to a fixed value.
      *
      * Header handling is relaxed so that the parser never consumes, validates or rejects the first record: every record
      * reaches us untouched and [[HeaderMode]] alone decides what the first one is.
      *
      * The remaining settings are ones we do not expose at all
     *   pinning them keeps them in order to have consistent `equals`/`hashCode`.
      */
    private def overrideSettingsForCompatibility(apacheBuilder: ApacheCSVBuilder): ApacheCSVBuilder =
      apacheBuilder
        .setHeader(null.asInstanceOf[Array[String]]*) //ok - header is represented as CSVHeader
        .setSkipHeaderRecord(false)// ok - we use our HeaderMode
        .setDuplicateHeaderMode(DuplicateHeaderMode.ALLOW_ALL) //ok predefined formats always have allow all, and this only is taken into account when setHeader was called
        .setAllowMissingColumnNames(true) //ok predefined formats always have allow all, and this only is taken into account when setHeader was called
        .setHeaderComments(null.asInstanceOf[Array[String]]*) //todo - ok, we can just add an CommentRecord before any row
        .setIgnoreHeaderCase(false) //access only //todo ok
        .setAutoFlush(false) //not needed
        .setMaxRows(0) //not needed - flows is lazy so user can take(maxRows)
        // empty lines must reach us as records, so that they can be represented on the data level
  //      .setIgnoreEmptyLines(false) //todo - shouldnt be here, format define, we should allow users setting itpredefined formats should carry this unaffected because of compatibility with targets like excel

      //factors influencing output shape/type
      //HeaderMode.Force (introduced by us) - forces first line as header //todo influencing output shape, true = no header in output, only data
    //HeaderMode.TryParseAsData (introduced by us) - optional header //todo influencing output shape, true = no header in output, only data
    //HeaderMode.matching (introduced by us) - optional header //todo influencing output shape, true = no header in output, only data


