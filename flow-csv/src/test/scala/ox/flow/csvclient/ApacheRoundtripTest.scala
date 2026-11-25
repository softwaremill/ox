package ox.flow.csvclient

import org.apache.commons.csv.{CSVFormat as ApacheCSVFormat, CSVPrinter, CSVRecord}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.io.{StringReader, StringWriter}
import scala.jdk.CollectionConverters.*

/** Does Apache support parse→print roundtrip? What happens to empty lines, nulls, trims? */
class ApacheRoundtripTest extends AnyFlatSpec with Matchers:

  private def parse(format: ApacheCSVFormat, input: String): List[CSVRecord] =
    format.parse(StringReader(input)).getRecords.asScala.toList

  private def printRecords(format: ApacheCSVFormat, records: List[CSVRecord]): String =
    val sw = StringWriter()
    val printer = CSVPrinter(sw, format)
    records.foreach { r =>
      val values = (0 until r.size).map(i => r.get(i)) // get returns null for null fields
      printer.printRecord(values*)
    }
    printer.flush()
    sw.toString
  end printRecords

  private def roundtrip(format: ApacheCSVFormat, input: String): String =
    val records = parse(format, input)
    printRecords(format, records)

  // --- RFC4180 basic ---

  "RFC4180 roundtrip" should "preserve normal data" in:
    val input = "a,b,c\nd,e,f\n"
    val output = roundtrip(ApacheCSVFormat.RFC4180, input)
    info(s"in:  ${input.replace("\n", "\\n")}")
    info(s"out: ${output.replace("\n", "\\n").replace("\r", "\\r")}")

  it should "show what happens to empty lines" in:
    val input = "a,b\n\nc,d\n"
    val output = roundtrip(ApacheCSVFormat.RFC4180, input)
    info(s"in:  ${input.replace("\n", "\\n")}")
    info(s"out: ${output.replace("\n", "\\n").replace("\r", "\\r")}")

  // --- RFC4180 + nullString="" ---

  "RFC4180 + nullString roundtrip" should "show null→print behavior" in:
    val format = ApacheCSVFormat.RFC4180.builder().setNullString("").get()
    val input = "a,b\n\nc,d\n"
    val records = parse(format, input)
    records.foreach { r =>
      info(s"parsed: size=${r.size}, values=${(0 until r.size).map(i => Option(r.get(i)))}")
    }
    val output = printRecords(format, records)
    info(s"in:  ${input.replace("\n", "\\n")}")
    info(s"out: ${output.replace("\n", "\\n").replace("\r", "\\r")}")

  it should "roundtrip empty fields" in:
    val format = ApacheCSVFormat.RFC4180.builder().setNullString("").get()
    val input = "a,,c\n,b,\n"
    val output = roundtrip(format, input)
    info(s"in:  ${input.replace("\n", "\\n")}")
    info(s"out: ${output.replace("\n", "\\n").replace("\r", "\\r")}")

  // --- DEFAULT (ignoreEmptyLines) ---

  "DEFAULT roundtrip" should "lose empty lines" in:
    val input = "a,b\n\nc,d\n"
    val output = roundtrip(ApacheCSVFormat.DEFAULT, input)
    info(s"in:  ${input.replace("\n", "\\n")}")
    info(s"out: ${output.replace("\n", "\\n").replace("\r", "\\r")}")

  // --- trim ---

  "trim roundtrip" should "lose whitespace" in:
    val format = ApacheCSVFormat.RFC4180.builder().setTrim(true).get()
    val input = " a , b \n c , d \n"
    val output = roundtrip(format, input)
    info(s"in:  ${input.replace("\n", "\\n")}")
    info(s"out: ${output.replace("\n", "\\n").replace("\r", "\\r")}")

  // --- trim + nullString ---

  "trim + nullString roundtrip" should "show combined loss" in:
    val format = ApacheCSVFormat.RFC4180.builder().setTrim(true).setNullString("").get()
    val input = "a,b\n\n , \nc,d\n"
    val records = parse(format, input)
    records.foreach { r =>
      info(s"parsed: size=${r.size}, values=${(0 until r.size).map(i => Option(r.get(i)))}")
    }
    val output = printRecords(format, records)
    info(s"in:  ${input.replace("\n", "\\n")}")
    info(s"out: ${output.replace("\n", "\\n").replace("\r", "\\r")}")

  // --- Single column ---

  "single column roundtrip" should "show empty line behavior" in:
    val input = "foo\n\nbar\n"
    val output = roundtrip(ApacheCSVFormat.RFC4180, input)
    info(s"in:  ${input.replace("\n", "\\n")}")
    info(s"out: ${output.replace("\n", "\\n").replace("\r", "\\r")}")

  "single column + nullString roundtrip" should "show null printing" in:
    val format = ApacheCSVFormat.RFC4180.builder().setNullString("").get()
    val input = "foo\n\nbar\n"
    val records = parse(format, input)
    records.foreach { r =>
      info(s"parsed: size=${r.size}, values=${(0 until r.size).map(i => Option(r.get(i)))}")
    }
    val output = printRecords(format, records)
    info(s"in:  ${input.replace("\n", "\\n")}")
    info(s"out: ${output.replace("\n", "\\n").replace("\r", "\\r")}")

  // --- MySQL (nullString=\N) ---

  "MySQL roundtrip" should "preserve \\N nulls" in:
    val input = "a\t\\N\tb\n"
    val records = parse(ApacheCSVFormat.MYSQL, input)
    records.foreach { r =>
      info(s"parsed: size=${r.size}, values=${(0 until r.size).map(i => Option(r.get(i)))}")
    }
    val output = printRecords(ApacheCSVFormat.MYSQL, records)
    info(s"in:  ${input.replace("\n", "\\n").replace("\t", "\\t")}")
    info(s"out: ${output.replace("\n", "\\n").replace("\t", "\\t")}")

  // --- Ragged row roundtrip ---

  "ragged row roundtrip" should "not pad missing columns" in:
    val input = "a,b,c\nfoo\nd,e,f\n"
    val output = roundtrip(ApacheCSVFormat.RFC4180, input)
    info(s"in:  ${input.replace("\n", "\\n")}")
    info(s"out: ${output.replace("\n", "\\n").replace("\r", "\\r")}")

end ApacheRoundtripTest
