package ox.flow.csv.format

import org.apache.commons.csv.CSVFormat as ApacheCSVFormat

/** The settings a [[CsvFormat]] is described by: plain, immutable, side effect free data that can be copied and passed around freely.
 *
 * Start from [[Settings.Default]] (or any format's own [[CsvFormat.settings]]) and `copy` the settings that differ:
 * {{{
 * CsvFormat(Settings.Default.copy(delimiter = ";", trim = true))
 * }}}
 * @note Serialization is not supported
 */
final case class Settings private[csv](
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
