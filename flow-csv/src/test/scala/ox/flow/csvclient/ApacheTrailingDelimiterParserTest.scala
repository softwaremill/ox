package ox.flow.csvclient

import org.apache.commons.csv.CSVFormat as ApacheCSVFormat
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.io.StringReader
import scala.jdk.CollectionConverters.*

class ApacheTrailingDelimiterParserTest extends AnyFlatSpec with Matchers:

  private def show(format: ApacheCSVFormat, input: String) =
    format.parse(StringReader(input)).getRecords.asScala.toList.map { r =>
      s"size=${r.size} charPos=${r.getCharacterPosition} [${(0 until r.size).map(i => Option(r.get(i)).map(s => s"'$s'").getOrElse("NULL")).mkString(", ")}]"
    }

  "trailingDelimiter=true parser" should "strip last delimiter from normal row" in:
    val fmt = ApacheCSVFormat.RFC4180.builder().setTrailingDelimiter(true).get()
    info(s"'a,b,c,' → ${show(fmt, "a,b,c,")}")
    info(s"'a,b,c'  → ${show(fmt, "a,b,c")}")

  it should "handle empty line after row with trailing delim" in:
    val fmt = ApacheCSVFormat.RFC4180.builder().setTrailingDelimiter(true).get()
    info(s"'a,b,\\n\\n' → ${show(fmt, "a,b,\n\n")}")
    info(s"'a,b,\\n\\nc,d,' → ${show(fmt, "a,b,\n\nc,d,")}")

  it should "handle empty line without trailing delim setting" in:
    val fmt = ApacheCSVFormat.RFC4180
    info(s"'a,b\\n\\n' → ${show(fmt, "a,b\n\n")}")
    info(s"'a,b\\n\\nc,d' → ${show(fmt, "a,b\n\nc,d")}")

  it should "handle multiple empty lines with trailing delim" in:
    val fmt = ApacheCSVFormat.RFC4180.builder().setTrailingDelimiter(true).get()
    info(s"'a,b,\\n\\n\\nc,d,' → ${show(fmt, "a,b,\n\n\nc,d,")}")

  it should "handle row ending with trailing delim + empty line + data" in:
    val fmt = ApacheCSVFormat.RFC4180.builder().setTrailingDelimiter(true).get()
    info(s"'a,b,\\n\\nc,d,' → ${show(fmt, "a,b,\n\nc,d,")}")

  it should "handle empty line that looks like trailing delim (single comma)" in:
    val fmt = ApacheCSVFormat.RFC4180.builder().setTrailingDelimiter(true).get()
    info(s"'a,b\\n,\\nc,d' → ${show(fmt, "a,b\n,\nc,d")}")

  "trailingDelimiter=true + ignoreEmptyLines=true" should "show interaction" in:
    val fmt = ApacheCSVFormat.RFC4180.builder().setTrailingDelimiter(true).setIgnoreEmptyLines(true).get()
    info(s"'a,b,\\n\\nc,d,' → ${show(fmt, "a,b,\n\nc,d,")}")
    info(s"'a,b,\\n\\n\\nc,d,' → ${show(fmt, "a,b,\n\n\nc,d,")}")

  "trailingDelimiter=true + ignoreEmptyLines=false" should "show interaction" in:
    val fmt = ApacheCSVFormat.RFC4180.builder().setTrailingDelimiter(true).setIgnoreEmptyLines(false).get()
    info(s"'a,b,\\n\\nc,d,' → ${show(fmt, "a,b,\n\nc,d,")}")
    info(s"'a,b,\\n\\n\\nc,d,' → ${show(fmt, "a,b,\n\n\nc,d,")}")

  "trailingDelimiter=false + empty lines" should "show baseline" in:
    val fmt = ApacheCSVFormat.RFC4180.builder().setIgnoreEmptyLines(false).get()
    info(s"'a,b\\n\\nc,d' → ${show(fmt, "a,b\n\nc,d")}")
    info(s"'a,b\\n\\n\\nc,d' → ${show(fmt, "a,b\n\n\nc,d")}")

end ApacheTrailingDelimiterParserTest
