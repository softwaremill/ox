package ox.flow.csvclient

import org.apache.commons.csv.CSVFormat as ApacheCSVFormat
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.io.{StringReader, StringWriter}
import scala.jdk.CollectionConverters.*

class ApacheTrailingDelimiterTest extends AnyFlatSpec with Matchers:

  private def parse(format: ApacheCSVFormat, input: String) =
    format.parse(StringReader(input)).getRecords.asScala.toList

  private def show(format: ApacheCSVFormat, input: String) =
    parse(format, input).map { r =>
      (0 until r.size).map(i => Option(r.get(i)).map(s => s"'$s'").getOrElse("NULL")).mkString("[", ", ", "]")
    }

  "trailingDelimiter on parser" should "show effect on normal row" in:
    val fmt = ApacheCSVFormat.RFC4180.builder().setTrailingDelimiter(true).get()
    val fmtNo = ApacheCSVFormat.RFC4180
    info(s"trailing=true,  'a,b,c,':  ${show(fmt, "a,b,c,")}")
    info(s"trailing=false, 'a,b,c,':  ${show(fmtNo, "a,b,c,")}")
    info(s"trailing=true,  'a,b,c':   ${show(fmt, "a,b,c")}")
    info(s"trailing=false, 'a,b,c':   ${show(fmtNo, "a,b,c")}")

  it should "show effect on empty line" in:
    val fmt = ApacheCSVFormat.RFC4180.builder().setTrailingDelimiter(true).get()
    val fmtNo = ApacheCSVFormat.RFC4180
    info(s"trailing=true,  empty line:  ${show(fmt, "a,b\n\nc,d")}")
    info(s"trailing=false, empty line:  ${show(fmtNo, "a,b\n\nc,d")}")

  it should "show effect on single comma (empty line with trailing delim)" in:
    val fmt = ApacheCSVFormat.RFC4180.builder().setTrailingDelimiter(true).get()
    val fmtNo = ApacheCSVFormat.RFC4180
    info(s"trailing=true,  ',':  ${show(fmt, "a,b\n,\nc,d")}")
    info(s"trailing=false, ',':  ${show(fmtNo, "a,b\n,\nc,d")}")

  "trailingDelimiter on printer" should "show output" in:
    val fmt = ApacheCSVFormat.RFC4180.builder().setTrailingDelimiter(true).get()
    val fmtNo = ApacheCSVFormat.RFC4180
    val sw1 = StringWriter()
    val sw2 = StringWriter()
    fmt.print(sw1).printRecord("a", "b", "c")
    fmtNo.print(sw2).printRecord("a", "b", "c")
    info(s"trailing=true:  '${sw1.toString.trim}'")
    info(s"trailing=false: '${sw2.toString.trim}'")

  it should "show empty record output" in:
    val fmt = ApacheCSVFormat.RFC4180.builder().setTrailingDelimiter(true).get()
    val sw = StringWriter()
    fmt.print(sw).printRecord("")
    info(s"trailing=true, print '':  '${sw.toString.trim}'")

end ApacheTrailingDelimiterTest
