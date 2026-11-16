package ox.flow.csv.format.typed

import org.apache.commons.csv.CSVFormat as ApacheCSVFormat
import ox.flow.Flow
import ox.flow.csv.format.{Format, HeaderMode, PrinterFormat}
import ox.flow.csv.CsvRecord.*

import scala.annotation.targetName
import scala.compiletime.{erasedValue, error, summonFrom}
import scala.deriving.Mirror.ProductOf
import scala.util.NotGiven


object PredefinedFormats:

  private def overrideSettingsForCompatibility[R](apacheFormat: ApacheCSVFormat, headerMode: HeaderMode): R = ???

  /**
   * RFC 4180 compliant CSV format.
   * - Optional header
   * - Ragged rows allowed - requires capability to handle missing data for all [[T]] fields except first one (enforced at compiletime)
   * - Empty lines are interpreted as legitimate ragged row with value of first column being empty string
   * - Delimiter: comma, Quote: `"`, Record separator: `\r\n`
   */
  inline def Rfc4180[T](using mir: ProductOf[T]): Format[Flow[HeaderRecord | T]]
    & HasBuilder[HeaderModeSetting[HeaderMode.TryParseAsData]] =
    //todo require ragged row handling at compiletime
    overrideSettingsForCompatibility(apacheFormat = ApacheCSVFormat.RFC4180, headerMode = HeaderMode.TryParseAsData)

  /**
   * Standard CSV format (RFC 4180 + ignoreEmptyLines).
   * - Optional header
   * - Empty lines ignored silently
   * - Delimiter: comma, Quote: `"`, Record separator: `\r\n`
   */
  def Default[T]: Format[Flow[HeaderRecord | T]]
    & HasBuilder[IgnoreEmptyLines & HeaderModeSetting[HeaderMode.TryParseAsData]] =
    overrideSettingsForCompatibility(apacheFormat = ApacheCSVFormat.DEFAULT, headerMode = HeaderMode.TryParseAsData)

  /**
   * RFC 4180 with strict parsing - no empty column fallback for non-string columns, throws on ragged rows.
   * - Optional header
   * - Ragged rows fail at runtime (missing column on any field type, including String)
   * - Empty cells on non-String fields fail at runtime
   * - Delimiter: comma, Quote: `"`, Record separator: `\r\n`
   */
  def Rfc4180Strict[T]: Format[Flow[HeaderRecord | T]]
    & HasBuilder[DisallowRaggedRows & HeaderModeSetting[HeaderMode.TryParseAsData]] =
    //todo - throw on ragged row
    overrideSettingsForCompatibility(apacheFormat = ApacheCSVFormat.RFC4180, headerMode = HeaderMode.TryParseAsData)

  /**
   * Microsoft Excel CSV format with null handling for empty cells.
   * - Optional header
   * - Allows missing column names, trailing data, lenient EOF
   * - Delimiter: comma, Quote: `"`, Record separator: `\r\n`
   */
  def Excel[T]: Format[Flow[HeaderRecord | T]]
    & HasBuilder[HeaderModeSetting[HeaderMode.TryParseAsData]] =
    //todo - validate missing for MirroredTypes.tail
    overrideSettingsForCompatibility(apacheFormat = ApacheCSVFormat.EXCEL, headerMode = HeaderMode.TryParseAsData)

  /**
   * Microsoft Excel CSV format with strict parsing - no empty column fallback for non-string columns, ParsingException is thrown when empty string is encountered for non-string column.
   * - Optional header
   * - Empty cells on non-String fields will fail at runtime
   * - Allows missing column names, trailing data, lenient EOF
   * - Delimiter: comma, Quote: `"`, Record separator: `\r\n`
   *
   */
  def ExcelStrict[T]: Format[Flow[HeaderRecord | T]]
    & HasBuilder[HeaderModeSetting[HeaderMode.TryParseAsData]] =
    overrideSettingsForCompatibility(apacheFormat = ApacheCSVFormat.EXCEL, headerMode = HeaderMode.TryParseAsData)

  /**
   * Tab-delimited format (TDF).
   * - Optional header
   * - Empty lines ignored
   * - Ignores surrounding spaces
   * - Delimiter: tab, Quote: `"`, Record separator: `\r\n`
   */
  def Tdf[T]: Format[Flow[HeaderRecord | T]]
    & HasBuilder[IgnoreEmptyLines & HeaderModeSetting[HeaderMode.TryParseAsData]] =
    overrideSettingsForCompatibility(apacheFormat = ApacheCSVFormat.TDF, headerMode = HeaderMode.TryParseAsData)

  /**
   * Informix UNLOAD format used by `UNLOAD TO file_name`.
   * - No header assumed
   * - Empty lines ignored
   * - Delimiter: pipe (`|`), Escape: `\`, Quote: `"`, Record separator: `\n`
   */
  def InformixUnload[T]: Format[Flow[T]]
    & HasBuilder[IgnoreEmptyLines] =
    overrideSettingsForCompatibility(apacheFormat = ApacheCSVFormat.INFORMIX_UNLOAD, headerMode = HeaderMode.TryParseAsData)

  /**
   * Informix CSV UNLOAD format (escaping disabled).
   * - No header
   * - Empty lines ignored
   * - Delimiter: comma, Quote: `"`, Record separator: `\n`
   */
  def InformixUnloadCsv[T]: Format[Flow[T]]
    & HasBuilder[IgnoreEmptyLines] =
    overrideSettingsForCompatibility(apacheFormat = ApacheCSVFormat.INFORMIX_UNLOAD_CSV, headerMode = HeaderMode.TryParseAsData)

  /**
   * MongoDB CSV format used by `mongoexport` (printer only, parsing not supported).
   * - Header always expected/required
   * - Empty lines ignored
   * - Delimiter: comma, Escape: `"`, Quote: `"`, QuoteMode: MINIMAL
   */
  def MongoDbCsv[T]: PrinterFormat[(HeaderRecord, Flow[T])]
    & HasBuilder[IgnoreEmptyLines & HeaderModeSetting[HeaderMode.Force]] =
    overrideSettingsForCompatibility(apacheFormat = ApacheCSVFormat.MONGODB_CSV, headerMode = HeaderMode.Force)

  /**
   * MongoDB TSV format used by `mongoexport` (printer only, parsing not supported).
   * - Header always expected/required
   * - Empty lines ignored
   * - Delimiter: tab, Escape: `"`, Quote: `"`, QuoteMode: MINIMAL
   */
  def MongoDbTsv[T]: PrinterFormat[(HeaderRecord, Flow[T])]
    & HasBuilder[IgnoreEmptyLines & HeaderModeSetting[HeaderMode.Force]] =
    overrideSettingsForCompatibility(apacheFormat = ApacheCSVFormat.MONGODB_TSV, headerMode = HeaderMode.Force)

  /**
   * MySQL format used by `SELECT INTO OUTFILE` and `LOAD DATA INFILE`.
   * - No header
   * - Null String: `\N` - [[T]] codecs need to be able to handle nulls
   * - Delimiter: tab, Escape: `\`, No quoting, Record separator: `\n`
   */
  inline def MySql[T]: Format[Flow[T]]
    & HasBuilder[NullString["\\N"]] =
    overrideSettingsForCompatibility(apacheFormat = ApacheCSVFormat.MYSQL, headerMode = HeaderMode.TryParseAsData)

  /**
   * Oracle SQL*Loader format.
   * - No header
   * - Null String: `\N` - [[T]] codecs need to be able to handle nulls
   * - Delimiter: comma, Escape: `\`, Quote: `"`, Trims values, Record separator: system line separator
   */
  inline def Oracle[T]: Format[Flow[T]]
    & HasBuilder[NullString["\\N"]] =
    overrideSettingsForCompatibility(apacheFormat = ApacheCSVFormat.ORACLE, headerMode = HeaderMode.TryParseAsData)

  /**
   * Default PostgreSQL CSV format used by the COPY operation.
   * - No header
   * - Null String: "" - [[T]] codecs need to be able to handle nulls
   */
  inline def PostgreSqlCsv[T](using mir: ProductOf[T]): Format[Flow[T]]
    & HasBuilder[NullString[""]] =
    //todo - require null handling
    overrideSettingsForCompatibility(apacheFormat = ApacheCSVFormat.POSTGRESQL_CSV, headerMode = HeaderMode.TryParseAsData)

  /**
   * PostgreSQL text format used by the COPY operation.
   * - No header
   * - Null String: `\N` - [[T]] codecs need to be able to handle nulls
   * - Delimiter: tab, Escape: `\`, No quoting, Record separator: `\n`
   */
  inline def PostgreSqlText[T]: Format[Flow[T]]
    & HasBuilder[NullString["\\N"]] =
    overrideSettingsForCompatibility(apacheFormat = ApacheCSVFormat.POSTGRESQL_TEXT, headerMode = HeaderMode.TryParseAsData)