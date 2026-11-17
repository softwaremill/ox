package ox.flow.csv.format.typed

import org.apache.commons.csv.CSVFormat as ApacheCSVFormat
import ox.flow.Flow
import org.apache.commons.csv.CSVFormat as ApacheCSVFormat
import ox.flow.csv.format.compat.ApacheCsvFormatCompat
import ox.flow.csv.format.{Format, HeaderMode, PrinterFormat, Settings}
import ox.flow.csv.CsvRecord.*

import scala.annotation.targetName
import scala.compiletime.{erasedValue, error, summonFrom}
import scala.deriving.Mirror.ProductOf
import scala.util.NotGiven


object PredefinedFormats:

  type Rfc4180Builder = HasBuilder[HeaderModeSetting[HeaderMode.TryParseAsData]]
  /**
   * RFC 4180 compliant CSV format.
   * - Optional header
   * - Ragged rows allowed - requires capability to handle missing data for all [[T]] fields except first one (enforced at compiletime)
   * - Empty lines are interpreted as legitimate ragged row with value of first column being empty string
   * - Delimiter: comma, Quote: `"`, Record separator: `\r\n`
   */
  inline def Rfc4180[T](using mir: ProductOf[T]): Format[Flow[HeaderRecord | T]] & Rfc4180Builder =
    FlowFormatImpl.optionalHeader[T, HeaderModeSetting[HeaderMode.TryParseAsData]](Rfc4180Apache, TypedFormatBuilder.Instance[HeaderModeSetting[HeaderMode.TryParseAsData]](Rfc4180))

  type DefaultBuilder = HasBuilder[IgnoreEmptyLines & HeaderModeSetting[HeaderMode.TryParseAsData]]
  /**
   * Standard CSV format (RFC 4180 + ignoreEmptyLines).
   * - Optional header
   * - Empty lines ignored silently
   * - Delimiter: comma, Quote: `"`, Record separator: `\r\n`
   */
  inline def Default[T](using mir: ProductOf[T]): Format[Flow[HeaderRecord | T]] & DefaultBuilder =
    FlowFormatImpl.optionalHeader[T, IgnoreEmptyLines & HeaderModeSetting[HeaderMode.TryParseAsData]](DefaultApache, TypedFormatBuilder.Instance[IgnoreEmptyLines & HeaderModeSetting[HeaderMode.TryParseAsData]](Default))

  type Rfc4180StrictBuilder = HasBuilder[DisallowRaggedRows & HeaderModeSetting[HeaderMode.TryParseAsData]]
  /**
   * RFC 4180 with strict parsing - no empty column fallback for non-string columns, throws on ragged rows.
   * - Optional header
   * - Ragged rows fail at runtime (missing column on any field type, including String)
   * - Empty cells on non-String fields fail at runtime
   * - Delimiter: comma, Quote: `"`, Record separator: `\r\n`
   */
  inline def Rfc4180Strict[T](using mir: ProductOf[T]): Format[Flow[HeaderRecord | T]] & Rfc4180StrictBuilder =
    FlowFormatImpl.optionalHeader[T, DisallowRaggedRows & HeaderModeSetting[HeaderMode.TryParseAsData]](Rfc4180Apache, TypedFormatBuilder.Instance[DisallowRaggedRows & HeaderModeSetting[HeaderMode.TryParseAsData]](Rfc4180))

  type ExcelBuilder = HasBuilder[HeaderModeSetting[HeaderMode.TryParseAsData]]
  /**
   * Microsoft Excel CSV format with null handling for empty cells.
   * - Optional header
   * - Allows missing column names, trailing data, lenient EOF
   * - Delimiter: comma, Quote: `"`, Record separator: `\r\n`
   */
  inline def Excel[T](using mir: ProductOf[T]): Format[Flow[HeaderRecord | T]] & ExcelBuilder =
    FlowFormatImpl.optionalHeader[T, HeaderModeSetting[HeaderMode.TryParseAsData]](ExcelApache, TypedFormatBuilder.Instance[HeaderModeSetting[HeaderMode.TryParseAsData]](Excel))

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
    FlowFormatImpl.optionalHeader[T, HeaderModeSetting[HeaderMode.TryParseAsData]](ExcelApache, TypedFormatBuilder.Instance[HeaderModeSetting[HeaderMode.TryParseAsData]](Excel))

  /** The settings [[Tdf]] is built at. */
  type TdfBuilder = HasBuilder[IgnoreEmptyLines & HeaderModeSetting[HeaderMode.TryParseAsData]]
  /**
   * Tab-delimited format (TDF).
   * - Optional header
   * - Empty lines ignored
   * - Ignores surrounding spaces
   * - Delimiter: tab, Quote: `"`, Record separator: `\r\n`
   */
  inline def Tdf[T](using mir: ProductOf[T]): Format[Flow[HeaderRecord | T]] & TdfBuilder =
    FlowFormatImpl.optionalHeader[T, IgnoreEmptyLines & HeaderModeSetting[HeaderMode.TryParseAsData]](TdfApache, TypedFormatBuilder.Instance[IgnoreEmptyLines & HeaderModeSetting[HeaderMode.TryParseAsData]](Tdf))

  /** The settings [[InformixUnload]] is built at. */
  type InformixUnloadBuilder = HasBuilder[IgnoreEmptyLines]
  /**
   * Informix UNLOAD format used by `UNLOAD TO file_name`.
   * - No header assumed
   * - Empty lines ignored
   * - Delimiter: pipe (`|`), Escape: `\`, Quote: `"`, Record separator: `\n`
   */
  inline def InformixUnload[T](using mir: ProductOf[T]): Format[Flow[T]] & InformixUnloadBuilder =
    FlowFormatImpl.noHeader[T, IgnoreEmptyLines](InformixUnloadApache, TypedFormatBuilder.Instance[IgnoreEmptyLines](InformixUnload))

  /** The settings [[InformixUnloadCsv]] is built at. */
  type InformixUnloadCsvBuilder = HasBuilder[IgnoreEmptyLines]
  /**
   * Informix CSV UNLOAD format (escaping disabled).
   * - No header
   * - Empty lines ignored
   * - Delimiter: comma, Quote: `"`, Record separator: `\n`
   */
  inline def InformixUnloadCsv[T](using mir: ProductOf[T]): Format[Flow[T]] & InformixUnloadCsvBuilder =
    FlowFormatImpl.noHeader[T, IgnoreEmptyLines](InformixUnloadCsvApache, TypedFormatBuilder.Instance[IgnoreEmptyLines](InformixUnloadCsv))

  /** The settings [[MongoDbCsv]] is built at. */
  type MongoDbCsvBuilder = HasBuilder[IgnoreEmptyLines & HeaderModeSetting[HeaderMode.Force]]
  /**
   * MongoDB CSV format used by `mongoexport` (printer only, parsing not supported).
   * - Header always expected/required
   * - Empty lines ignored
   * - Delimiter: comma, Escape: `"`, Quote: `"`, QuoteMode: MINIMAL
   */
  inline def MongoDbCsv[T](using mir: ProductOf[T]): PrinterFormat[(HeaderRecord, Flow[T])] & MongoDbCsvBuilder =
    FlowFormatImpl.forcedHeader[T, IgnoreEmptyLines & HeaderModeSetting[HeaderMode.Force]](MongoDbCsvApache, TypedFormatBuilder.Instance[IgnoreEmptyLines & HeaderModeSetting[HeaderMode.Force]](MongoDbCsv))

  /** The settings [[MongoDbTsv]] is built at. */
  type MongoDbTsvBuilder = HasBuilder[IgnoreEmptyLines & HeaderModeSetting[HeaderMode.Force]]
  /**
   * MongoDB TSV format used by `mongoexport` (printer only, parsing not supported).
   * - Header always expected/required
   * - Empty lines ignored
   * - Delimiter: tab, Escape: `"`, Quote: `"`, QuoteMode: MINIMAL
   */
  inline def MongoDbTsv[T](using mir: ProductOf[T]): PrinterFormat[(HeaderRecord, Flow[T])] & MongoDbTsvBuilder =
    FlowFormatImpl.forcedHeader[T, IgnoreEmptyLines & HeaderModeSetting[HeaderMode.Force]](MongoDbTsvApache, TypedFormatBuilder.Instance[IgnoreEmptyLines & HeaderModeSetting[HeaderMode.Force]](MongoDbTsv))

  /** The settings [[MySql]] is built at. */
  type MySqlBuilder = HasBuilder[NullString["\\N"]]
  /**
   * MySQL format used by `SELECT INTO OUTFILE` and `LOAD DATA INFILE`.
   * - No header
   * - Null String: `\N` - [[T]] codecs need to be able to handle nulls
   * - Delimiter: tab, Escape: `\`, No quoting, Record separator: `\n`
   */
  inline def MySql[T](using mir: ProductOf[T]): Format[Flow[T]] & MySqlBuilder =
    FlowFormatImpl.noHeader[T, NullString["\\N"]](MySqlApache, TypedFormatBuilder.Instance[NullString["\\N"]](MySql))

  /** The settings [[Oracle]] is built at. */
  type OracleBuilder = HasBuilder[NullString["\\N"]]
  /**
   * Oracle SQL*Loader format.
   * - No header
   * - Null String: `\N` - [[T]] codecs need to be able to handle nulls
   * - Delimiter: comma, Escape: `\`, Quote: `"`, Trims values, Record separator: system line separator
   */
  inline def Oracle[T](using mir: ProductOf[T]): Format[Flow[T]] & OracleBuilder =
    FlowFormatImpl.noHeader[T, NullString["\\N"]](OracleApache, TypedFormatBuilder.Instance[NullString["\\N"]](Oracle))

  /** The settings [[PostgreSqlCsv]] is built at. */
  type PostgreSqlCsvBuilder = HasBuilder[NullString[""]]
  /**
   * Default PostgreSQL CSV format used by the COPY operation.
   * - No header
   * - Null String: "" - [[T]] codecs need to be able to handle nulls
   */
  inline def PostgreSqlCsv[T](using mir: ProductOf[T]): Format[Flow[T]] & PostgreSqlCsvBuilder =
    FlowFormatImpl.noHeader[T, NullString[""]](PostgreSqlCsvApache, TypedFormatBuilder.Instance[NullString[""]](PostgreSqlCsv))

  /** The settings [[PostgreSqlText]] is built at. */
  type PostgreSqlTextBuilder = HasBuilder[NullString["\\N"]]
  /**
   * PostgreSQL text format used by the COPY operation.
   * - No header
   * - Null String: `\N` - [[T]] codecs need to be able to handle nulls
   * - Delimiter: tab, Escape: `\`, No quoting, Record separator: `\n`
   */
  inline def PostgreSqlText[T](using mir: ProductOf[T]): Format[Flow[T]] & PostgreSqlTextBuilder =
    FlowFormatImpl.noHeader[T, NullString["\\N"]](PostgreSqlTextApache, TypedFormatBuilder.Instance[NullString["\\N"]](PostgreSqlText))

  /** Adapts a predefined Apache format settings to most permissive values
   * This is needed because otherwise apache parser would drop data of fail fast before ox could apply its own
   * format settings like [[HeaderMode]]
   */
  private def overrideSettingsForCompatibility(apacheFormat: ApacheCSVFormat, headerMode: HeaderMode): Settings =
    ApacheCsvFormatCompat.settingsOf(apacheFormat, headerMode)

  private val Default: Settings = overrideSettingsForCompatibility(apacheFormat = ApacheCSVFormat.DEFAULT, headerMode = HeaderMode.TryParseAsData)

  private val Rfc4180: Settings = overrideSettingsForCompatibility(apacheFormat = ApacheCSVFormat.RFC4180, headerMode = HeaderMode.TryParseAsData)

  private val Excel: Settings = overrideSettingsForCompatibility(apacheFormat = ApacheCSVFormat.EXCEL, headerMode = HeaderMode.TryParseAsData)

  private val Tdf: Settings = overrideSettingsForCompatibility(apacheFormat = ApacheCSVFormat.TDF, headerMode = HeaderMode.TryParseAsData)

  private val InformixUnload: Settings = overrideSettingsForCompatibility(apacheFormat = ApacheCSVFormat.INFORMIX_UNLOAD, headerMode = HeaderMode.TryParseAsData)

  private val InformixUnloadCsv: Settings = overrideSettingsForCompatibility(apacheFormat = ApacheCSVFormat.INFORMIX_UNLOAD_CSV, headerMode = HeaderMode.TryParseAsData)

  private val MongoDbCsv: Settings = overrideSettingsForCompatibility(apacheFormat = ApacheCSVFormat.MONGODB_CSV, headerMode = HeaderMode.TryParseAsData)

  private val MongoDbTsv: Settings = overrideSettingsForCompatibility(apacheFormat = ApacheCSVFormat.MONGODB_TSV, headerMode = HeaderMode.TryParseAsData)

  private val MySql: Settings = overrideSettingsForCompatibility(apacheFormat = ApacheCSVFormat.MYSQL, headerMode = HeaderMode.TryParseAsData)

  private val Oracle: Settings = overrideSettingsForCompatibility(apacheFormat = ApacheCSVFormat.ORACLE, headerMode = HeaderMode.TryParseAsData)

  private val PostgreSqlCsv: Settings = overrideSettingsForCompatibility(apacheFormat = ApacheCSVFormat.POSTGRESQL_CSV, headerMode = HeaderMode.TryParseAsData)

  private val PostgreSqlText: Settings = overrideSettingsForCompatibility(apacheFormat = ApacheCSVFormat.POSTGRESQL_TEXT, headerMode = HeaderMode.TryParseAsData)

  /** Each dialect's Apache format, built once. Constructing it is what validates the settings, and it is
    * what reading and writing actually use, so the predefined formats share one per dialect rather than
    * rebuilding it per call. */
  private val DefaultApache: ApacheCSVFormat = ApacheCsvFormatCompat.toApache(Default)
  private val Rfc4180Apache: ApacheCSVFormat = ApacheCsvFormatCompat.toApache(Rfc4180)
  private val ExcelApache: ApacheCSVFormat = ApacheCsvFormatCompat.toApache(Excel)
  private val TdfApache: ApacheCSVFormat = ApacheCsvFormatCompat.toApache(Tdf)
  private val InformixUnloadApache: ApacheCSVFormat = ApacheCsvFormatCompat.toApache(InformixUnload)
  private val InformixUnloadCsvApache: ApacheCSVFormat = ApacheCsvFormatCompat.toApache(InformixUnloadCsv)
  private val MongoDbCsvApache: ApacheCSVFormat = ApacheCsvFormatCompat.toApache(MongoDbCsv)
  private val MongoDbTsvApache: ApacheCSVFormat = ApacheCsvFormatCompat.toApache(MongoDbTsv)
  private val MySqlApache: ApacheCSVFormat = ApacheCsvFormatCompat.toApache(MySql)
  private val OracleApache: ApacheCSVFormat = ApacheCsvFormatCompat.toApache(Oracle)
  private val PostgreSqlCsvApache: ApacheCSVFormat = ApacheCsvFormatCompat.toApache(PostgreSqlCsv)
  private val PostgreSqlTextApache: ApacheCSVFormat = ApacheCsvFormatCompat.toApache(PostgreSqlText)
