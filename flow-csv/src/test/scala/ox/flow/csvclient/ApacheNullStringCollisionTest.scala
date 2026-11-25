package ox.flow.csvclient

import org.apache.commons.csv.{CSVFormat as ApacheCSVFormat, QuoteMode}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.io.{StringReader, StringWriter}
import scala.jdk.CollectionConverters.*

class ApacheNullStringCollisionTest extends AnyFlatSpec with Matchers:

  private def printOut(fmt: ApacheCSVFormat, values: Seq[Any]): String =
    val sw = StringWriter()
    val p = fmt.print(sw)
    p.printRecord(values*)
    p.flush()
    sw.toString.trim

  private def parseBack(fmt: ApacheCSVFormat, input: String) =
    fmt.parse(StringReader(input)).getRecords.asScala.toList.map { r =>
      (0 until r.size).map(i => Option(r.get(i)).map(s => s"'$s'").getOrElse("<NULL>")).mkString("[", ", ", "]")
    }

  "nullString=\"null\" with literal \"null\" and real null" should "show collision on print" in:
    val fmt = ApacheCSVFormat.RFC4180.builder().setNullString("null").get()
    val out = printOut(fmt, Seq("null", null, "c"))
    info(s"""print Seq("null", null, "c") => '$out'""")
    info(s"parse back                    => ${parseBack(fmt, out)}")

  it should "show same with QuoteMode.ALL_NON_NULL (quoting protects)" in:
    val fmt = ApacheCSVFormat.RFC4180.builder().setNullString("null").setQuoteMode(QuoteMode.ALL_NON_NULL).get()
    val out = printOut(fmt, Seq("null", null, "c"))
    info(s"""ALL_NON_NULL print => '$out'""")
    info(s"ALL_NON_NULL parse => ${parseBack(fmt, out)}")

  "format with NO nullString" should "show what a real null prints as" in:
    val formats = List(
      "RFC4180" -> ApacheCSVFormat.RFC4180,
      "EXCEL" -> ApacheCSVFormat.EXCEL,
      "TDF" -> ApacheCSVFormat.TDF,
      "INFORMIX_UNLOAD" -> ApacheCSVFormat.INFORMIX_UNLOAD
    )
    formats.foreach { (n, f) =>
      val out = printOut(f, Seq("a", null, "c"))
      info(f"$n%-18s nullString=${Option(f.getNullString)} print Seq(a,null,c) => '$out'")
    }

  it should "show roundtrip for no-nullString format" in:
    val fmt = ApacheCSVFormat.RFC4180
    val out = printOut(fmt, Seq("a", null, "c"))
    info(s"RFC4180 print => '$out'")
    info(s"RFC4180 parse => ${parseBack(fmt, out)}")

end ApacheNullStringCollisionTest
