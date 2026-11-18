package ox.flow.csvclient

import org.apache.commons.csv.CSVFormat as ApacheCSVFormat
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.io.StringReader
import scala.jdk.CollectionConverters.*

class ApacheTrailingDelimiterEofTest extends AnyFlatSpec with Matchers:

  private def show(format: ApacheCSVFormat, input: String) =
    format.parse(StringReader(input)).getRecords.asScala.toList.map { r =>
      s"size=${r.size} [${(0 until r.size).map(i => Option(r.get(i)).map(s => s"'$s'").getOrElse("NULL")).mkString(", ")}]"
    }

  "row without trailing delim = EOF?" should "stop parsing after row missing trailing comma" in:
    val fmt = ApacheCSVFormat.RFC4180.builder().setTrailingDelimiter(true).get()
    // row WITH trailing, then row WITHOUT trailing, then more data
    info(s"'a,b,\\nc,d\\ne,f,' trailing=true: ${show(fmt, "a,b,\nc,d\ne,f,")}")

  it should "parse all rows when all have trailing comma" in:
    val fmt = ApacheCSVFormat.RFC4180.builder().setTrailingDelimiter(true).get()
    info(s"'a,b,\\nc,d,\\ne,f,' trailing=true: ${show(fmt, "a,b,\nc,d,\ne,f,")}")

  it should "parse when last row lacks trailing comma" in:
    val fmt = ApacheCSVFormat.RFC4180.builder().setTrailingDelimiter(true).get()
    info(s"'a,b,\\nc,d,\\ne,f' trailing=true: ${show(fmt, "a,b,\nc,d,\ne,f")}")

  it should "stop at row without trailing when followed by more" in:
    val fmt = ApacheCSVFormat.RFC4180.builder().setTrailingDelimiter(true).get()
    info(s"'a,b,\\nc,d\\ne,f,\\ng,h,' trailing=true: ${show(fmt, "a,b,\nc,d\ne,f,\ng,h,")}")

  "empty line is row without trailing delim" should "act as EOF" in:
    val fmt = ApacheCSVFormat.RFC4180.builder().setTrailingDelimiter(true).get()
    // empty line = no trailing comma → EOF?
    info(s"'a,b,\\n\\ne,f,' trailing=true: ${show(fmt, "a,b,\n\ne,f,")}")

  it should "compare: comma-only line has trailing" in:
    val fmt = ApacheCSVFormat.RFC4180.builder().setTrailingDelimiter(true).get()
    // comma = [''] with trailing stripped → has content, not EOF
    info(s"'a,b,\\n,\\ne,f,' trailing=true: ${show(fmt, "a,b,\n,\ne,f,")}")

end ApacheTrailingDelimiterEofTest
