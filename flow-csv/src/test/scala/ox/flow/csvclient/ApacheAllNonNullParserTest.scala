package ox.flow.csvclient

import org.apache.commons.csv.{CSVFormat as ApacheCSVFormat, QuoteMode}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.io.StringReader
import scala.jdk.CollectionConverters.*

/** Does ALL_NON_NULL make the parser treat unquoted fields as null? */
class ApacheAllNonNullParserTest extends AnyFlatSpec with Matchers:

  private def parse(format: ApacheCSVFormat, input: String) =
    format.parse(StringReader(input)).getRecords.asScala.toList

  private def show(format: ApacheCSVFormat, input: String) =
    parse(format, input).map { r =>
      (0 until r.size).map(i => Option(r.get(i)).map(s => s"'$s'").getOrElse("NULL")).toList
    }

  // Unquoted non-empty field with ALL_NON_NULL — does it become null?
  "ALL_NON_NULL" should "show if unquoted 'foo' becomes null" in:
    val fmt = ApacheCSVFormat.RFC4180.builder().setQuoteMode(QuoteMode.ALL_NON_NULL).get()
    val input = "\"a\",b,\"c\""
    info(s"ALL_NON_NULL, unquoted b: ${show(fmt, input)}")

  it should "show if unquoted empty becomes null" in:
    val fmt = ApacheCSVFormat.RFC4180.builder().setQuoteMode(QuoteMode.ALL_NON_NULL).get()
    val input = "\"a\",,\"c\""
    info(s"ALL_NON_NULL, unquoted empty: ${show(fmt, input)}")

  it should "show if quoted empty stays non-null" in:
    val fmt = ApacheCSVFormat.RFC4180.builder().setQuoteMode(QuoteMode.ALL_NON_NULL).get()
    val input = "\"a\",\"\",\"c\""
    info(s"ALL_NON_NULL, quoted empty: ${show(fmt, input)}")

  // Compare with ALL
  "ALL" should "show same inputs" in:
    val fmt = ApacheCSVFormat.RFC4180.builder().setQuoteMode(QuoteMode.ALL).get()
    info(s"ALL, unquoted b:     ${show(fmt, "\"a\",b,\"c\"")}")
    info(s"ALL, unquoted empty: ${show(fmt, "\"a\",,\"c\"")}")
    info(s"ALL, quoted empty:   ${show(fmt, "\"a\",\"\",\"c\"")}")

  // Compare with MINIMAL (default RFC4180)
  "MINIMAL" should "show same inputs" in:
    info(s"MINIMAL, unquoted b:     ${show(ApacheCSVFormat.RFC4180, "\"a\",b,\"c\"")}")
    info(s"MINIMAL, unquoted empty: ${show(ApacheCSVFormat.RFC4180, "\"a\",,\"c\"")}")
    info(s"MINIMAL, quoted empty:   ${show(ApacheCSVFormat.RFC4180, "\"a\",\"\",\"c\"")}")

  // ALL_NON_NULL without any quoting in input
  "ALL_NON_NULL with fully unquoted input" should "show what happens" in:
    val fmt = ApacheCSVFormat.RFC4180.builder().setQuoteMode(QuoteMode.ALL_NON_NULL).get()
    val input = "a,b,c\nd,,f"
    info(s"ALL_NON_NULL, all unquoted: ${show(fmt, input)}")

  // NON_NUMERIC
  "NON_NUMERIC" should "show parser behavior on unquoted" in:
    val fmt = ApacheCSVFormat.RFC4180.builder().setQuoteMode(QuoteMode.NON_NUMERIC).get()
    info(s"NON_NUMERIC, unquoted b:     ${show(fmt, "\"a\",b,\"c\"")}")
    info(s"NON_NUMERIC, unquoted empty: ${show(fmt, "\"a\",,\"c\"")}")
    info(s"NON_NUMERIC, quoted empty:   ${show(fmt, "\"a\",\"\",\"c\"")}")

end ApacheAllNonNullParserTest
