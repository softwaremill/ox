package ox.flow.csvclient

import org.apache.commons.csv.CSVFormat as ApacheCSVFormat
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.io.StringReader
import scala.jdk.CollectionConverters.*

/** Tests Apache Commons CSV: trim/ignoreSurroundingSpaces interaction with nullString.
  *
  * Key questions:
  *   1. Does trim happen before or after nullString comparison?
  *   2. " " (space-only field) with trim + nullString="" → null or ""?
  *   3. " foo " with trim + nullString="foo" → null?
  *   4. ignoreSurroundingSpaces vs trim — same or different?
  *   5. Quoted " foo " with trim — does quoting protect from trim?
  */
class ApacheTrimNullStringTest extends AnyFlatSpec with Matchers:

  private def parse(format: ApacheCSVFormat, input: String) =
    format.parse(StringReader(input)).getRecords.asScala.toList

  private def vals(format: ApacheCSVFormat, input: String) =
    parse(format, input).map { r =>
      (0 until r.size).map(i => Option(r.get(i)).map(s => s"'$s'").getOrElse("NULL")).toList
    }

  // --- trim + nullString="" ---

  "trim + nullString=\"\"" should "show if space-only field becomes null" in:
    val format = ApacheCSVFormat.RFC4180
      .builder()
      .setTrim(true)
      .setNullString("")
      .get()
    val input = "a, ,c\na,,c\na,  ,c"
    val records = vals(format, input)
    records.foreach(r => info(s"$r"))
    // " " trimmed → "" → nullString match → null?

  it should "show if padded value matching nullString becomes null" in:
    val format = ApacheCSVFormat.RFC4180
      .builder()
      .setTrim(true)
      .setNullString("foo")
      .get()
    val input = "a, foo ,c\na,foo,c\na, bar ,c"
    val records = vals(format, input)
    records.foreach(r => info(s"$r"))
    // " foo " trimmed → "foo" → nullString match → null?

  // --- ignoreSurroundingSpaces + nullString="" ---

  "ignoreSurroundingSpaces + nullString=\"\"" should "show if space-only field becomes null" in:
    val format = ApacheCSVFormat.RFC4180
      .builder()
      .setIgnoreSurroundingSpaces(true)
      .setNullString("")
      .get()
    val input = "a, ,c\na,,c\na,  ,c"
    val records = vals(format, input)
    records.foreach(r => info(s"$r"))

  it should "show if padded value matching nullString becomes null" in:
    val format = ApacheCSVFormat.RFC4180
      .builder()
      .setIgnoreSurroundingSpaces(true)
      .setNullString("foo")
      .get()
    val input = "a, foo ,c\na,foo,c\na, bar ,c"
    val records = vals(format, input)
    records.foreach(r => info(s"$r"))

  // --- trim vs ignoreSurroundingSpaces difference ---

  "trim vs ignoreSurroundingSpaces" should "show difference on quoted values" in:
    val trimFmt = ApacheCSVFormat.RFC4180.builder().setTrim(true).get()
    val issFmt = ApacheCSVFormat.RFC4180.builder().setIgnoreSurroundingSpaces(true).get()
    val input = "\" foo \",bar"
    info(s"trim:    ${vals(trimFmt, input)}")
    info(s"ignSurr: ${vals(issFmt, input)}")
    // trim strips inside quotes too? ignoreSurroundingSpaces only outside?

  it should "show difference on unquoted values" in:
    val trimFmt = ApacheCSVFormat.RFC4180.builder().setTrim(true).get()
    val issFmt = ApacheCSVFormat.RFC4180.builder().setIgnoreSurroundingSpaces(true).get()
    val input = " foo , bar "
    info(s"trim:    ${vals(trimFmt, input)}")
    info(s"ignSurr: ${vals(issFmt, input)}")

  // --- trim + nullString + empty line ---

  "trim + nullString=\"\" + empty line" should "show combined behavior" in:
    val format = ApacheCSVFormat.RFC4180
      .builder()
      .setTrim(true)
      .setNullString("")
      .get()
    val input = "a,b\n\n  \nc,d"
    val records = parse(format, input)
    records.foreach { r =>
      val v = (0 until r.size).map(i => Option(r.get(i)).map(s => s"'$s'").getOrElse("NULL")).toList
      info(s"size=${r.size}, values=$v")
    }

  // --- Oracle format (has trim built in) ---

  "Oracle format (trim=true)" should "show nullString=\\N with padded \\N" in:
    val input = "a, \\N ,c\na,\\N,c"
    val records = vals(ApacheCSVFormat.ORACLE, input)
    records.foreach(r => info(s"Oracle: $r"))

  // --- TDF (ignoreSurroundingSpaces=true) ---

  "TDF (ignoreSurroundingSpaces)" should "show with nullString added" in:
    val format = ApacheCSVFormat.TDF.builder().setNullString("").get()
    val input = "a\t \tc\na\t\tc"
    val records = vals(format, input)
    records.foreach(r => info(s"TDF: $r"))

  // --- trim + nullString + single column ---

  "single column + trim + nullString=\"\"" should "show space-only line behavior" in:
    val format = ApacheCSVFormat.RFC4180
      .builder()
      .setTrim(true)
      .setNullString("")
      .get()
    val input = "foo\n \nbar\n  \nbaz"
    val records = vals(format, input)
    records.foreach(r => info(s"$r"))

  // --- ignoreEmptyLines + trim + space-only line ---

  "ignoreEmptyLines + trim" should "show if space-only line is treated as empty" in:
    val format = ApacheCSVFormat.DEFAULT
      .builder()
      .setTrim(true)
      .get()
    val input = "foo\n \nbar\n  \nbaz"
    val records = vals(format, input)
    records.foreach(r => info(s"$r"))
    info(s"Record count: ${records.length} (3=spaces treated as empty, 5=spaces preserved)")

end ApacheTrimNullStringTest
