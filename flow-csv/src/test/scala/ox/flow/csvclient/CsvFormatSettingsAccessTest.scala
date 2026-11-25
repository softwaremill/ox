package ox.flow.csvclient

import org.apache.commons.csv.CSVFormat as ApacheCSVFormat
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.io.StringReader
import scala.jdk.CollectionConverters.*

/** Tests bare Apache Commons CSV behavior to confirm assumptions about predefined formats. */
class CsvFormatSettingsAccessTest extends AnyFlatSpec with Matchers:

  private def parse(format: ApacheCSVFormat, input: String) =
    format.parse(StringReader(input)).getRecords.asScala.toList

  private def recordValues(format: ApacheCSVFormat, input: String) =
    parse(format, input).map(r => (0 until r.size).map(r.get).toList)

  "DEFAULT" should "ignore empty lines" in:
    val result = recordValues(ApacheCSVFormat.DEFAULT, "a,b\n\nc,d")
    result shouldBe List(List("a", "b"), List("c", "d"))

  "RFC4180" should "preserve empty lines as records" in:
    val result = recordValues(ApacheCSVFormat.RFC4180, "a,b\n\nc,d")
    result.length shouldBe 3

  "RFC4180" should "parse ragged rows without error" in:
    val result = recordValues(ApacheCSVFormat.RFC4180, "a,b,c\nfoo\nd,e,f")
    result(0) shouldBe List("a", "b", "c")
    result(1) shouldBe List("foo")
    result(2) shouldBe List("d", "e", "f")

  "EXCEL" should "preserve empty lines as records" in:
    val result = recordValues(ApacheCSVFormat.EXCEL, "a,b\n\nc,d")
    result.length shouldBe 3

  "MYSQL" should "use tab delimiter" in:
    val result = recordValues(ApacheCSVFormat.MYSQL, "a\tb\tc\nd\te\tf")
    result shouldBe List(List("a", "b", "c"), List("d", "e", "f"))

  "MYSQL" should "interpret \\N as null" in:
    val records = parse(ApacheCSVFormat.MYSQL, "a\t\\N\tb")
    records.head.get(1) shouldBe null

  "POSTGRESQL_CSV" should "interpret empty string as null" in:
    val records = parse(ApacheCSVFormat.POSTGRESQL_CSV, "a,,b")
    records.head.get(1) shouldBe null

  "POSTGRESQL_TEXT" should "interpret \\N as null" in:
    val records = parse(ApacheCSVFormat.POSTGRESQL_TEXT, "a\t\\N\tb")
    records.head.get(1) shouldBe null

  "empty line record" should "have size 1 with empty string value in RFC4180" in:
    val result = parse(ApacheCSVFormat.RFC4180, "a,b\n\nfoo\nc,d")
    result.foreach(r => info(s"size=${r.size}, values=${(0 until r.size).map(r.get).toList}"))
    val emptyLineRecord = result(1)
    emptyLineRecord.size shouldBe 1
    emptyLineRecord.get(0) shouldBe ""
    val raggedRecord = result(2)
    raggedRecord.size shouldBe 1
    raggedRecord.get(0) shouldBe "foo"

  "isConsistent" should "return true for all records when no header is set" in:
    val result = parse(ApacheCSVFormat.RFC4180, "a,b,c\nfoo\n\nd,e,f")
    result.foreach(r => info(s"size=${r.size}, isConsistent=${r.isConsistent}, values=${(0 until r.size).map(r.get).toList}"))
    // without header, is isConsistent meaningful?
    result(0).isConsistent shouldBe true // 3 cols
    result(1).isConsistent shouldBe true // 1 col (ragged) - still true?
    result(2).isConsistent shouldBe true // empty line - still true?
    result(3).isConsistent shouldBe true // 3 cols

  it should "return false for inconsistent records when header is set" in:
    val format = ApacheCSVFormat.RFC4180.builder().setHeader("a", "b", "c").setSkipHeaderRecord(false).get()
    val result = parse(format, "a,b,c\nfoo\n\nd,e,f")
    result.foreach(r => info(s"size=${r.size}, isConsistent=${r.isConsistent}, values=${(0 until r.size).map(r.get).toList}"))

  "ignoreEmptyLines with single string column" should "strip truly empty lines but preserve quoted empty strings" in:
    // DEFAULT has ignoreEmptyLines=true
    val result = recordValues(ApacheCSVFormat.DEFAULT, "foo\n\n\"\"\nbar\n\n")
    result.foreach(r => info(s"values=$r"))
    result shouldBe List(List("foo"), List(""), List("bar"))

  it should "lose unquoted empty string values that look like empty lines" in:
    // single column, unquoted empty = empty line = stripped
    val result = recordValues(ApacheCSVFormat.DEFAULT, "foo\n\nbar")
    result shouldBe List(List("foo"), List("bar")) // the empty line between is gone

  it should "preserve empty lines when ignoreEmptyLines is false" in:
    val result = recordValues(ApacheCSVFormat.RFC4180, "foo\n\nbar")
    result.foreach(r => info(s"values=$r"))
    result shouldBe List(List("foo"), List(""), List("bar"))

end CsvFormatSettingsAccessTest
