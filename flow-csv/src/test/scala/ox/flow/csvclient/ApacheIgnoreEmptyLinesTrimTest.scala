package ox.flow.csvclient

import org.apache.commons.csv.CSVFormat as ApacheCSVFormat
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.io.StringReader
import scala.jdk.CollectionConverters.*

/** Tests ignoreEmptyLines interaction with trim and nullString — all combos. */
class ApacheIgnoreEmptyLinesTrimTest extends AnyFlatSpec with Matchers:

  private def parse(format: ApacheCSVFormat, input: String) =
    format.parse(StringReader(input)).getRecords.asScala.toList

  private def show(format: ApacheCSVFormat, input: String) =
    parse(format, input).map { r =>
      val v = (0 until r.size).map(i => Option(r.get(i)).map(s => s"'$s'").getOrElse("NULL")).toList
      s"size=${r.size} $v"
    }

  // Multi-column input with empty lines, space-only lines, mixed
  private val multiColInput = "a,b\n\n ,x\n  , \n\nc,d"
  // Single-column variant
  private val singleColInput = "foo\n\n \n  \n\nbar"

  // --- ignoreEmptyLines=false (RFC4180 base) ---

  "no ignoreEmptyLines, no trim, no nullString" should "preserve everything" in:
    show(ApacheCSVFormat.RFC4180, multiColInput).foreach(r => info(r))

  "no ignoreEmptyLines, trim, no nullString" should "trim but keep all lines" in:
    val fmt = ApacheCSVFormat.RFC4180.builder().setTrim(true).get()
    show(fmt, multiColInput).foreach(r => info(r))

  "no ignoreEmptyLines, trim, nullString=\"\"" should "trim then null" in:
    val fmt = ApacheCSVFormat.RFC4180.builder().setTrim(true).setNullString("").get()
    show(fmt, multiColInput).foreach(r => info(r))

  "no ignoreEmptyLines, no trim, nullString=\"\"" should "null empty but not spaces" in:
    val fmt = ApacheCSVFormat.RFC4180.builder().setNullString("").get()
    show(fmt, multiColInput).foreach(r => info(r))

  // --- ignoreEmptyLines=true (DEFAULT base) ---

  "ignoreEmptyLines, no trim, no nullString" should "drop empty, keep spaces" in:
    show(ApacheCSVFormat.DEFAULT, multiColInput).foreach(r => info(r))
    val count = parse(ApacheCSVFormat.DEFAULT, multiColInput).length
    info(s"count=$count")

  "ignoreEmptyLines, trim, no nullString" should "drop empty, keep space-only (trimmed)" in:
    val fmt = ApacheCSVFormat.DEFAULT.builder().setTrim(true).get()
    show(fmt, multiColInput).foreach(r => info(r))
    val count = parse(fmt, multiColInput).length
    info(s"count=$count")

  "ignoreEmptyLines, no trim, nullString=\"\"" should "drop empty, spaces stay as-is" in:
    val fmt = ApacheCSVFormat.DEFAULT.builder().setNullString("").get()
    show(fmt, multiColInput).foreach(r => info(r))
    val count = parse(fmt, multiColInput).length
    info(s"count=$count")

  "ignoreEmptyLines, trim, nullString=\"\"" should "drop empty, trim spaces→null" in:
    val fmt = ApacheCSVFormat.DEFAULT.builder().setTrim(true).setNullString("").get()
    show(fmt, multiColInput).foreach(r => info(r))
    val count = parse(fmt, multiColInput).length
    info(s"count=$count")

  // --- Single column variants ---

  "single col: ignoreEmptyLines, no trim" should "drop empty, keep space-only" in:
    show(ApacheCSVFormat.DEFAULT, singleColInput).foreach(r => info(r))
    info(s"count=${parse(ApacheCSVFormat.DEFAULT, singleColInput).length}")

  "single col: ignoreEmptyLines, trim" should "drop empty, space-only survives (trimmed to '')" in:
    val fmt = ApacheCSVFormat.DEFAULT.builder().setTrim(true).get()
    show(fmt, singleColInput).foreach(r => info(r))
    info(s"count=${parse(fmt, singleColInput).length}")

  "single col: ignoreEmptyLines, trim, nullString=\"\"" should "drop empty, space→trim→null" in:
    val fmt = ApacheCSVFormat.DEFAULT.builder().setTrim(true).setNullString("").get()
    show(fmt, singleColInput).foreach(r => info(r))
    info(s"count=${parse(fmt, singleColInput).length}")

  // --- Edge: what about \t as "empty" with TDF? ---

  "TDF (ignoreEmptyLines + ignoreSurroundingSpaces)" should "show tab-only line behavior" in:
    val input = "a\tb\n\n\t\n\t \t\nc\td"
    show(ApacheCSVFormat.TDF, input).foreach(r => info(r))
    info(s"count=${parse(ApacheCSVFormat.TDF, input).length}")

  "TDF + nullString=\"\"" should "show tab-only + null interaction" in:
    val fmt = ApacheCSVFormat.TDF.builder().setNullString("").get()
    val input = "a\tb\n\n\t\nc\td"
    show(fmt, input).foreach(r => info(r))
    info(s"count=${parse(fmt, input).length}")

end ApacheIgnoreEmptyLinesTrimTest
