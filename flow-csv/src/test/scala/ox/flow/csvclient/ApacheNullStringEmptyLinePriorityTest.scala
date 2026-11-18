package ox.flow.csvclient

import org.apache.commons.csv.CSVFormat as ApacheCSVFormat
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.io.StringReader
import scala.jdk.CollectionConverters.*

/** Tests Apache Commons CSV behavior re: null string vs empty line priority.
  *
  * Key questions:
  *   1. When nullString="" and an empty line appears → does Apache return null or ""?
  *   2. Does ignoreEmptyLines interact with nullString?
  *   3. Single-column behavior with nullString="" set.
  *   4. Multi-column: empty line record (size 1, value "") with nullString="" → is the single field null?
  */
class ApacheNullStringEmptyLinePriorityTest extends AnyFlatSpec with Matchers:

  private def parse(format: ApacheCSVFormat, input: String) =
    format.parse(StringReader(input)).getRecords.asScala.toList

  private def dumpRecords(format: ApacheCSVFormat, input: String) =
    parse(format, input).map { r =>
      val values = (0 until r.size).map(i => Option(r.get(i)))
      s"size=${r.size}, values=$values, isConsistent=${r.isConsistent}"
    }

  // --- Q1: RFC4180 (no ignoreEmptyLines) + nullString="" ---

  "RFC4180 with nullString=\"\"" should "show what Apache does with empty line" in:
    val format = ApacheCSVFormat.RFC4180.builder().setNullString("").get()
    val input = "a,b\n\nc,d"
    val records = parse(format, input)
    records.foreach(r => info(s"size=${r.size}, values=${(0 until r.size).map(i => s"${Option(r.get(i))}").toList}"))
    // empty line → record of size 1, value "" → does nullString convert that to null?
    records.length shouldBe 3
    val emptyLineRec = records(1)
    info(s"Empty line record: size=${emptyLineRec.size}, get(0)=${Option(emptyLineRec.get(0))}")

  it should "show what Apache does with actual empty field in multi-column row" in:
    val format = ApacheCSVFormat.RFC4180.builder().setNullString("").get()
    val input = "a,,c"
    val records = parse(format, input)
    records.foreach(r => info(s"size=${r.size}, values=${(0 until r.size).map(i => s"${Option(r.get(i))}").toList}"))
    val middleField = records.head.get(1)
    info(s"Middle empty field: ${Option(middleField)}")

  // --- Q2: Single column with nullString="" ---

  "single column with nullString=\"\"" should "show behavior for empty line" in:
    val format = ApacheCSVFormat.RFC4180.builder().setNullString("").get()
    val input = "foo\n\nbar"
    val records = parse(format, input)
    records.foreach(r => info(s"size=${r.size}, values=${(0 until r.size).map(i => s"${Option(r.get(i))}").toList}"))
    // Is the empty line's "" converted to null by nullString?
    info(s"Empty line in single-col: get(0)=${Option(records(1).get(0))}")

  "single column without nullString" should "show baseline behavior" in:
    val format = ApacheCSVFormat.RFC4180
    val input = "foo\n\nbar"
    val records = parse(format, input)
    records.foreach(r => info(s"size=${r.size}, values=${(0 until r.size).map(i => s"${Option(r.get(i))}").toList}"))
    info(s"Empty line in single-col (no nullString): get(0)=${Option(records(1).get(0))}")

  // --- Q3: ignoreEmptyLines + nullString interaction ---

  "DEFAULT (ignoreEmptyLines) with nullString=\"\"" should "still ignore empty lines" in:
    val format = ApacheCSVFormat.DEFAULT.builder().setNullString("").get()
    val input = "a,b\n\nc,d"
    val records = parse(format, input)
    records.foreach(r => info(s"size=${r.size}, values=${(0 until r.size).map(i => s"${Option(r.get(i))}").toList}"))
    info(s"Record count: ${records.length} (2 = empty lines ignored before nullString)")

  "DEFAULT (ignoreEmptyLines) with nullString=\"\" single column" should "show interaction" in:
    val format = ApacheCSVFormat.DEFAULT.builder().setNullString("").get()
    val input = "foo\n\nbar"
    val records = parse(format, input)
    records.foreach(r => info(s"size=${r.size}, values=${(0 until r.size).map(i => s"${Option(r.get(i))}").toList}"))
    info(s"Record count: ${records.length}")

  // --- Q4: nullString with various values, not just "" ---

  "nullString=\\N" should "not affect empty lines" in:
    val format = ApacheCSVFormat.RFC4180.builder().setNullString("\\N").get()
    val input = "a,b\n\nc,d"
    val records = parse(format, input)
    records.foreach(r => info(s"size=${r.size}, values=${(0 until r.size).map(i => s"${Option(r.get(i))}").toList}"))
    val emptyLineRec = records(1)
    info(s"Empty line with nullString=\\N: get(0)=${Option(emptyLineRec.get(0))}")

  // --- Q5: Ragged row behavior with nullString ---

  "ragged row with nullString=\"\"" should "show if missing columns become null" in:
    val format = ApacheCSVFormat.RFC4180
      .builder()
      .setNullString("")
      .setHeader("a", "b", "c")
      .setSkipHeaderRecord(false)
      .get()
    val input = "foo\na,b,c"
    val records = parse(format, input)
    records.foreach(r =>
      info(s"size=${r.size}, values=${(0 until r.size).map(i => s"${Option(r.get(i))}").toList}, isConsistent=${r.isConsistent}")
    )

  // --- Q6: Header mode with nullString --- does Apache apply nullString to the ragged row's existing field?

  "ragged single-field row with nullString=\"\"" should "convert the existing field to null too" in:
    val format = ApacheCSVFormat.RFC4180.builder().setNullString("").get()
    val input = "a,b,c\n\nd,e,f"
    val records = parse(format, input)
    records.foreach(r => info(s"size=${r.size}, values=${(0 until r.size).map(i => s"${Option(r.get(i))}").toList}"))
    // The empty line record has size=1, value="" — does nullString="" make it null?
    info(s"Empty line record field 0: ${Option(records(1).get(0))}")

  // --- Q7: quoted empty string with nullString="" ---

  "quoted empty string with nullString=\"\"" should "show if quoting protects from null conversion" in:
    val format = ApacheCSVFormat.RFC4180.builder().setNullString("").get()
    val input = "a,\"\",c\na,,c"
    val records = parse(format, input)
    records.foreach(r => info(s"size=${r.size}, values=${(0 until r.size).map(i => s"${Option(r.get(i))}").toList}"))
    info(s"Quoted empty: ${Option(records(0).get(1))}, Unquoted empty: ${Option(records(1).get(1))}")

end ApacheNullStringEmptyLinePriorityTest
