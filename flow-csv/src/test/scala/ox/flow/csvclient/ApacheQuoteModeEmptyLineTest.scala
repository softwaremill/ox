package ox.flow.csvclient

import org.apache.commons.csv.{CSVFormat as ApacheCSVFormat, QuoteMode}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.io.StringReader
import scala.jdk.CollectionConverters.*

/** How does QuoteMode affect parsing of empty lines? */
class ApacheQuoteModeEmptyLineTest extends AnyFlatSpec with Matchers:

  private def parse(format: ApacheCSVFormat, input: String) =
    format.parse(StringReader(input)).getRecords.asScala.toList

  private def show(format: ApacheCSVFormat, input: String) =
    parse(format, input).map { r =>
      val v = (0 until r.size).map(i => Option(r.get(i)).map(s => s"'$s'").getOrElse("NULL")).toList
      s"size=${r.size} $v charPos=${r.getCharacterPosition}"
    }

  "QuoteMode.ALL + empty line" should "show parsing behavior" in:
    val fmt = ApacheCSVFormat.RFC4180.builder().setQuoteMode(QuoteMode.ALL).get()
    val input = "\"a\",\"b\"\n\n\"c\",\"d\""
    show(fmt, input).foreach(r => info(r))

  "QuoteMode.ALL + empty line + nullString" should "show interaction" in:
    val fmt = ApacheCSVFormat.RFC4180.builder().setQuoteMode(QuoteMode.ALL).setNullString("").get()
    val input = "\"a\",\"b\"\n\n\"c\",\"d\""
    show(fmt, input).foreach(r => info(r))

  "QuoteMode.ALL_NON_NULL + empty line" should "show parsing behavior" in:
    val fmt = ApacheCSVFormat.RFC4180.builder().setQuoteMode(QuoteMode.ALL_NON_NULL).get()
    val input = "\"a\",\"b\"\n\n\"c\",\"d\""
    show(fmt, input).foreach(r => info(r))

  "QuoteMode.ALL_NON_NULL + empty line + nullString" should "show null vs empty" in:
    val fmt = ApacheCSVFormat.RFC4180.builder().setQuoteMode(QuoteMode.ALL_NON_NULL).setNullString("").get()
    val input = "\"a\",\"b\"\n\n\"c\",\"d\""
    show(fmt, input).foreach(r => info(r))

  // What about quoted empty string on empty line?
  "empty line vs quoted empty" should "show difference with ALL" in:
    val fmt = ApacheCSVFormat.RFC4180.builder().setQuoteMode(QuoteMode.ALL).get()
    val input = "\"a\"\n\n\"\"\n\"b\""
    info("Input: quoted-a, empty-line, quoted-empty, quoted-b")
    show(fmt, input).foreach(r => info(r))

  "empty line vs quoted empty + nullString" should "show with ALL" in:
    val fmt = ApacheCSVFormat.RFC4180.builder().setQuoteMode(QuoteMode.ALL).setNullString("").get()
    val input = "\"a\"\n\n\"\"\n\"b\""
    info("Input: quoted-a, empty-line, quoted-empty, quoted-b")
    show(fmt, input).foreach(r => info(r))

  // Multi-col with ALL - does empty line still produce size=1?
  "multi-col QuoteMode.ALL + empty line" should "show record sizes" in:
    val fmt = ApacheCSVFormat.RFC4180.builder().setQuoteMode(QuoteMode.ALL).get()
    val input = "\"a\",\"b\",\"c\"\n\n\"d\",\"e\",\"f\""
    show(fmt, input).foreach(r => info(r))

  // MINIMAL (default) for comparison
  "QuoteMode.MINIMAL + empty line" should "baseline" in:
    val input = "a,b\n\nc,d"
    show(ApacheCSVFormat.RFC4180, input).foreach(r => info(r))

end ApacheQuoteModeEmptyLineTest
