package ox.flow.csvclient

import org.apache.commons.csv.{CSVFormat as ApacheCSVFormat, CSVRecord}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.io.StringReader
import scala.jdk.CollectionConverters.*

/** What metadata does Apache CSVRecord expose that could distinguish empty line vs trimmed-to-empty vs actual data?
  */
class ApacheRecordMetadataTest extends AnyFlatSpec with Matchers:

  private def parse(format: ApacheCSVFormat, input: String): List[CSVRecord] =
    format.parse(StringReader(input)).getRecords.asScala.toList

  private def dumpAll(format: ApacheCSVFormat, input: String): Unit =
    parse(format, input).foreach { r =>
      val values = (0 until r.size).map(i => Option(r.get(i)).map(s => s"'$s'").getOrElse("NULL")).toList
      info(
        s"line=${r.getRecordNumber}, size=${r.size}, consistent=${r.isConsistent}, " +
          s"comment=${r.getComment}, charPos=${r.getCharacterPosition}, values=$values"
      )
    }

  // Can we use characterPosition to detect empty lines?
  "characterPosition" should "differ between empty line and space-only line" in:
    val fmt = ApacheCSVFormat.RFC4180.builder().setTrim(true).setNullString("").get()
    val input = "aaa\n\n \n  \nbbb"
    info("Input: 'aaa\\n\\n \\n  \\nbbb'")
    info("Positions: aaa=0, \\n=3, empty=4, \\n=4, ' '=5, \\n=6, '  '=7, \\n=9, bbb=10")
    dumpAll(fmt, input)

  "characterPosition without trim" should "show raw positions" in:
    val fmt = ApacheCSVFormat.RFC4180
    val input = "aaa\n\n \n  \nbbb"
    dumpAll(fmt, input)

  // Multi-column: empty line vs space-trimmed row
  "multi-column trim" should "show if empty line distinguishable from space row" in:
    val fmt = ApacheCSVFormat.RFC4180.builder().setTrim(true).setNullString("").get()
    val input = "a,b\n\n , \nc,d"
    info("Input: 'a,b\\n\\n , \\nc,d'")
    dumpAll(fmt, input)
    // empty line → size=1; " , " → size=2. Size differs!

  // But what about single-column?
  "single-column trim" should "show if size/position helps" in:
    val fmt = ApacheCSVFormat.RFC4180.builder().setTrim(true).setNullString("").get()
    val input = "foo\n\n \nbaz"
    info("Input: 'foo\\n\\n \\nbaz'")
    dumpAll(fmt, input)

  // What about getRecordNumber — line numbers?
  "recordNumber" should "show gaps from ignored lines" in:
    val fmt = ApacheCSVFormat.DEFAULT // ignoreEmptyLines=true
    val input = "foo\n\n\nbar\n\nbaz"
    info("DEFAULT (ignoreEmptyLines=true)")
    dumpAll(fmt, input)
    // Are record numbers sequential or do they skip?

  "recordNumber without ignoreEmptyLines" should "be sequential" in:
    val fmt = ApacheCSVFormat.RFC4180
    val input = "foo\n\n\nbar\n\nbaz"
    info("RFC4180 (ignoreEmptyLines=false)")
    dumpAll(fmt, input)

  // Check if CSVRecord has any other methods
  "CSVRecord methods" should "list what's available" in:
    val fmt = ApacheCSVFormat.RFC4180
    val input = "a,b"
    val r = parse(fmt, input).head
    info(s"class: ${r.getClass.getName}")
    info(s"methods: ${r.getClass.getMethods.map(_.getName).sorted.distinct.mkString(", ")}")

  // Header-based: isConsistent helps for multi-column
  "isConsistent with header" should "flag empty lines as inconsistent" in:
    val fmt = ApacheCSVFormat.RFC4180
      .builder()
      .setHeader("a", "b", "c")
      .setSkipHeaderRecord(false)
      .get()
    val input = "x,y,z\n\n , , \nfoo\na,b,c"
    dumpAll(fmt, input)

  // charPosition delta — can we compute original line length?
  "charPosition delta" should "reveal original content length" in:
    val fmt = ApacheCSVFormat.RFC4180.builder().setTrim(true).get()
    val input = "aaa\n\n \n  \nbbb"
    val records = parse(fmt, input)
    for i <- records.indices do
      val pos = records(i).getCharacterPosition
      val nextPos = if i + 1 < records.length then records(i + 1).getCharacterPosition else input.length.toLong + 1
      val lineLen = nextPos - pos - 1 // minus newline
      info(
        s"record $i: charPos=$pos, nextCharPos=$nextPos, originalLineLen=$lineLen, " +
          s"values=${(0 until records(i).size).map(j => Option(records(i).get(j)).map(s => s"'$s'").getOrElse("NULL")).toList}"
      )
    end for

end ApacheRecordMetadataTest
