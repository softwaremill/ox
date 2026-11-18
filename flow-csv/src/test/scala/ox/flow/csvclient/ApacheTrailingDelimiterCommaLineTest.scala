package ox.flow.csvclient

import org.apache.commons.csv.CSVFormat as ApacheCSVFormat
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.io.StringReader
import scala.jdk.CollectionConverters.*

class ApacheTrailingDelimiterCommaLineTest extends AnyFlatSpec with Matchers:

  private def show(format: ApacheCSVFormat, input: String) =
    format.parse(StringReader(input)).getRecords.asScala.toList.map { r =>
      s"size=${r.size} [${(0 until r.size).map(i => Option(r.get(i)).map(s => s"'$s'").getOrElse("NULL")).mkString(", ")}]"
    }

  "line with just comma" should "parse with trailing=true" in:
    val fmt = ApacheCSVFormat.RFC4180.builder().setTrailingDelimiter(true).get()
    info(s"',\\n' trailing=true:  ${show(fmt, ",\n")}")
    info(s"',\\na,b,' trailing=true:  ${show(fmt, ",\na,b,")}")

  it should "parse with trailing=false" in:
    val fmt = ApacheCSVFormat.RFC4180
    info(s"',\\n' trailing=false: ${show(fmt, ",\n")}")
    info(s"',\\na,b' trailing=false: ${show(fmt, ",\na,b")}")

  it should "parse with trailing=true + ignoreEmpty=true" in:
    val fmt = ApacheCSVFormat.RFC4180.builder().setTrailingDelimiter(true).setIgnoreEmptyLines(true).get()
    info(s"',\\n' trailing=true+ignoreEmpty:  ${show(fmt, ",\n")}")
    info(s"',\\na,b,' trailing=true+ignoreEmpty:  ${show(fmt, ",\na,b,")}")

  it should "parse with trailing=true + ignoreEmpty=false" in:
    val fmt = ApacheCSVFormat.RFC4180.builder().setTrailingDelimiter(true).setIgnoreEmptyLines(false).get()
    info(s"',\\n' trailing=true+!ignoreEmpty:  ${show(fmt, ",\n")}")
    info(s"',\\na,b,' trailing=true+!ignoreEmpty:  ${show(fmt, ",\na,b,")}")

  "just comma no newline" should "parse" in:
    val fmtT = ApacheCSVFormat.RFC4180.builder().setTrailingDelimiter(true).get()
    val fmtF = ApacheCSVFormat.RFC4180
    info(s"',' trailing=true:  ${show(fmtT, ",")}")
    info(s"',' trailing=false: ${show(fmtF, ",")}")

  "two commas" should "parse" in:
    val fmtT = ApacheCSVFormat.RFC4180.builder().setTrailingDelimiter(true).get()
    val fmtF = ApacheCSVFormat.RFC4180
    info(s"',,' trailing=true:  ${show(fmtT, ",,")}")
    info(s"',,' trailing=false: ${show(fmtF, ",,")}")

end ApacheTrailingDelimiterCommaLineTest
