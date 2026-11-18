package ox.flow.csvclient

import org.apache.commons.csv.{CSVFormat as ApacheCSVFormat, QuoteMode}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.io.{StringReader, StringWriter}
import scala.jdk.CollectionConverters.*

/** QuoteMode ALL/ALL_NON_NULL + nullString combos — parse and print behavior. */
class ApacheQuoteModeNullStringComboTest extends AnyFlatSpec with Matchers:

  private def parse(format: ApacheCSVFormat, input: String) =
    format.parse(StringReader(input)).getRecords.asScala.toList

  private def show(format: ApacheCSVFormat, input: String) =
    parse(format, input).map { r =>
      (0 until r.size).map(i => Option(r.get(i)).map(s => s"'$s'").getOrElse("NULL")).toList
    }

  private def printAndShow(format: ApacheCSVFormat, values: Seq[Any]*): String =
    val sw = StringWriter()
    val printer = format.print(sw)
    values.foreach(v => printer.printRecord(v*))
    printer.flush()
    sw.toString

  // --- ALL + nullString="" ---

  "ALL + nullString=\"\"" should "parse: unquoted empty, quoted empty, unquoted data" in:
    val fmt = ApacheCSVFormat.RFC4180.builder().setQuoteMode(QuoteMode.ALL).setNullString("").get()
    info(s"unquoted empty:  ${show(fmt, "a,,c")}")
    info(s"quoted empty:    ${show(fmt, "a,\"\",c")}")
    info(s"unquoted data:   ${show(fmt, "a,b,c")}")
    info(s"empty line:      ${show(fmt, "a,b\n\nc,d")}")

  it should "print: null vs empty string" in:
    val fmt = ApacheCSVFormat.RFC4180.builder().setQuoteMode(QuoteMode.ALL).setNullString("").get()
    info(s"print null:        '${printAndShow(fmt, Seq("a", null, "c")).trim}'")
    info(s"print empty str:   '${printAndShow(fmt, Seq("a", "", "c")).trim}'")
    info(s"print data:        '${printAndShow(fmt, Seq("a", "b", "c")).trim}'")

  // --- ALL_NON_NULL + nullString="" ---

  "ALL_NON_NULL + nullString=\"\"" should "parse: unquoted empty, quoted empty" in:
    val fmt = ApacheCSVFormat.RFC4180.builder().setQuoteMode(QuoteMode.ALL_NON_NULL).setNullString("").get()
    info(s"unquoted empty:  ${show(fmt, "a,,c")}")
    info(s"quoted empty:    ${show(fmt, "a,\"\",c")}")
    info(s"unquoted data:   ${show(fmt, "a,b,c")}")
    info(s"empty line:      ${show(fmt, "a,b\n\nc,d")}")

  it should "print: null vs empty string" in:
    val fmt = ApacheCSVFormat.RFC4180.builder().setQuoteMode(QuoteMode.ALL_NON_NULL).setNullString("").get()
    info(s"print null:        '${printAndShow(fmt, Seq("a", null, "c")).trim}'")
    info(s"print empty str:   '${printAndShow(fmt, Seq("a", "", "c")).trim}'")
    info(s"print data:        '${printAndShow(fmt, Seq("a", "b", "c")).trim}'")

  // --- ALL + nullString="NULL" (explicit word) ---

  "ALL + nullString=\"NULL\"" should "parse and print" in:
    val fmt = ApacheCSVFormat.RFC4180.builder().setQuoteMode(QuoteMode.ALL).setNullString("NULL").get()
    info(s"parse 'a,NULL,c':    ${show(fmt, "a,NULL,c")}")
    info(s"parse 'a,,c':        ${show(fmt, "a,,c")}")
    info(s"parse 'a,\"NULL\",c':  ${show(fmt, "a,\"NULL\",c")}")
    info(s"print null:          '${printAndShow(fmt, Seq("a", null, "c")).trim}'")
    info(s"print 'NULL' string: '${printAndShow(fmt, Seq("a", "NULL", "c")).trim}'")

  // --- ALL_NON_NULL + nullString="NULL" ---

  "ALL_NON_NULL + nullString=\"NULL\"" should "parse and print" in:
    val fmt = ApacheCSVFormat.RFC4180.builder().setQuoteMode(QuoteMode.ALL_NON_NULL).setNullString("NULL").get()
    info(s"parse 'a,NULL,c':     ${show(fmt, "a,NULL,c")}")
    info(s"parse 'a,,c':         ${show(fmt, "a,,c")}")
    info(s"parse 'a,\"NULL\",c':   ${show(fmt, "a,\"NULL\",c")}")
    info(s"parse 'a,\"\",c':      ${show(fmt, "a,\"\",c")}")
    info(s"print null:           '${printAndShow(fmt, Seq("a", null, "c")).trim}'")
    info(s"print 'NULL' string:  '${printAndShow(fmt, Seq("a", "NULL", "c")).trim}'")
    info(s"print empty string:   '${printAndShow(fmt, Seq("a", "", "c")).trim}'")

  // --- NON_NUMERIC + nullString="" ---

  "NON_NUMERIC + nullString=\"\"" should "parse: unquoted empty, quoted empty" in:
    val fmt = ApacheCSVFormat.RFC4180.builder().setQuoteMode(QuoteMode.NON_NUMERIC).setNullString("").get()
    info(s"unquoted empty:  ${show(fmt, "a,,c")}")
    info(s"quoted empty:    ${show(fmt, "a,\"\",c")}")

  it should "print: null vs empty" in:
    val fmt = ApacheCSVFormat.RFC4180.builder().setQuoteMode(QuoteMode.NON_NUMERIC).setNullString("").get()
    info(s"print null:        '${printAndShow(fmt, Seq("a", null, "c")).trim}'")
    info(s"print empty str:   '${printAndShow(fmt, Seq("a", "", "c")).trim}'")

end ApacheQuoteModeNullStringComboTest
