package ox.flow.csvclient

import org.apache.commons.csv.CSVFormat as ApacheCSVFormat
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.io.StringReader
import scala.jdk.CollectionConverters.*

/** What QuoteMode does each predefined format use? And how does it interact with their nullString? */
class ApacheQuoteModePerFormatTest extends AnyFlatSpec with Matchers:

  private def parse(format: ApacheCSVFormat, input: String) =
    format.parse(StringReader(input)).getRecords.asScala.toList

  private def showFormat(name: String, fmt: ApacheCSVFormat) =
    info(
      s"$name: quoteMode=${fmt.getQuoteMode}, nullString=${Option(fmt.getNullString)}, " +
        s"quoteChar=${Option(fmt.getQuoteCharacter)}, escape=${Option(fmt.getEscapeCharacter)}, " +
        s"delimiter='${fmt.getDelimiterString}'"
    )

  private def showParsed(fmt: ApacheCSVFormat, input: String) =
    parse(fmt, input).map { r =>
      (0 until r.size).map(i => Option(r.get(i)).map(s => s"'$s'").getOrElse("NULL")).toList
    }

  "all predefined formats" should "show their QuoteMode" in:
    showFormat("DEFAULT", ApacheCSVFormat.DEFAULT)
    showFormat("RFC4180", ApacheCSVFormat.RFC4180)
    showFormat("EXCEL", ApacheCSVFormat.EXCEL)
    showFormat("TDF", ApacheCSVFormat.TDF)
    showFormat("MYSQL", ApacheCSVFormat.MYSQL)
    showFormat("ORACLE", ApacheCSVFormat.ORACLE)
    showFormat("POSTGRESQL_CSV", ApacheCSVFormat.POSTGRESQL_CSV)
    showFormat("POSTGRESQL_TEXT", ApacheCSVFormat.POSTGRESQL_TEXT)
    showFormat("INFORMIX_UNLOAD", ApacheCSVFormat.INFORMIX_UNLOAD)
    showFormat("INFORMIX_UNLOAD_CSV", ApacheCSVFormat.INFORMIX_UNLOAD_CSV)
    showFormat("MONGODB_CSV", ApacheCSVFormat.MONGODB_CSV)
    showFormat("MONGODB_TSV", ApacheCSVFormat.MONGODB_TSV)

  // PostgreSQL CSV: nullString="" — does it use QuoteMode that affects parsing?
  "POSTGRESQL_CSV" should "show empty field behavior (nullString=\"\" already set)" in:
    // PostgreSQL CSV uses comma delimiter
    val input = "a,,c\na,\"\",c"
    info(s"unquoted empty + quoted empty: ${showParsed(ApacheCSVFormat.POSTGRESQL_CSV, input)}")

  "POSTGRESQL_TEXT" should "show empty field behavior (nullString=\\N)" in:
    // PostgreSQL TEXT uses tab delimiter
    val input = "a\t\t\\N\tb"
    info(s"empty tab + \\N: ${showParsed(ApacheCSVFormat.POSTGRESQL_TEXT, input)}")

  // MySQL: nullString=\N, no quoting
  "MYSQL" should "show empty field behavior" in:
    val input = "a\t\tb\na\t\\N\tb"
    info(s"empty tab + \\N: ${showParsed(ApacheCSVFormat.MYSQL, input)}")

  // Oracle: has trim
  "ORACLE" should "show empty field behavior" in:
    val input = "a,,c\na,\\N,c\na,\"\",c"
    info(s"empty + \\N + quoted empty: ${showParsed(ApacheCSVFormat.ORACLE, input)}")

  // MongoDB CSV/TSV
  "MONGODB_CSV" should "show quoteMode effect on empty" in:
    showFormat("MONGODB_CSV detail", ApacheCSVFormat.MONGODB_CSV)
    val input = "a,,c\na,\"\",c"
    info(s"empty + quoted empty: ${showParsed(ApacheCSVFormat.MONGODB_CSV, input)}")

  "MONGODB_TSV" should "show quoteMode effect on empty" in:
    showFormat("MONGODB_TSV detail", ApacheCSVFormat.MONGODB_TSV)
    val input = "a\t\tc\na\t\"\"\tc"
    info(s"empty + quoted empty: ${showParsed(ApacheCSVFormat.MONGODB_TSV, input)}")

end ApacheQuoteModePerFormatTest
