package ox.flow.csv

import org.apache.commons.csv.CSVFormat
import java.io.StringReader
import org.scalatest.funsuite.AnyFunSuite

class ApacheEmptyLineTest extends AnyFunSuite:

  private def parseAndDescribe(format: CSVFormat, input: String): Seq[String] =
    val parser = format.parse(StringReader(input))
    val records = parser.getRecords
    (0 until records.size).map { i =>
      val rec = records.get(i)
      val values = (0 until rec.size).map(j => s"\"${rec.get(j)}\"").mkString("[", ", ", "]")
      s"size=${rec.size}, values=$values"
    }
  end parseAndDescribe

  test("RFC4180 - empty line between data"):
    val results = parseAndDescribe(CSVFormat.RFC4180, "a,b,c\n\nd,e,f")
    println(s"RFC4180 empty line: $results")
    assert(results.length == 3)

  test("RFC4180 - CRLF empty line"):
    val results = parseAndDescribe(CSVFormat.RFC4180, "a,b,c\r\n\r\nd,e,f")
    println(s"RFC4180 CRLF empty: $results")
    assert(results.length == 3)

  test("DEFAULT - empty line between data (ignoreEmptyLines=true)"):
    val results = parseAndDescribe(CSVFormat.DEFAULT, "a,b,c\n\nd,e,f")
    println(s"DEFAULT empty line: $results")
    assert(results.length == 2)

  test("RFC4180 - line with only commas"):
    val results = parseAndDescribe(CSVFormat.RFC4180, "a,b,c\n,,\nd,e,f")
    println(s"RFC4180 only commas: $results")
    assert(results.length == 3)

  test("EXCEL - empty line between data"):
    val results = parseAndDescribe(CSVFormat.EXCEL, "a,b,c\n\nd,e,f")
    println(s"EXCEL empty line: $results")
    assert(results.length == 3)

  test("RFC4180 - multiple empty lines"):
    val results = parseAndDescribe(CSVFormat.RFC4180, "a,b,c\n\n\nd,e,f")
    println(s"RFC4180 multi empty: $results")

  test("RFC4180 - ragged row"):
    val results = parseAndDescribe(CSVFormat.RFC4180, "a,b,c\nfoo\nd,e,f")
    println(s"RFC4180 ragged: $results")
end ApacheEmptyLineTest
