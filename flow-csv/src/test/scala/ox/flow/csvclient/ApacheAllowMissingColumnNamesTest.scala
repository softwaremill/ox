package ox.flow.csvclient

import org.apache.commons.csv.CSVFormat as ApacheCSVFormat
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.io.StringReader
import scala.jdk.CollectionConverters.*

class ApacheAllowMissingColumnNamesTest extends AnyFlatSpec with Matchers:

  "all predefined formats" should "show allowMissingColumnNames" in:
    val formats = List(
      "DEFAULT" -> ApacheCSVFormat.DEFAULT,
      "RFC4180" -> ApacheCSVFormat.RFC4180,
      "EXCEL" -> ApacheCSVFormat.EXCEL,
      "TDF" -> ApacheCSVFormat.TDF,
      "MYSQL" -> ApacheCSVFormat.MYSQL,
      "ORACLE" -> ApacheCSVFormat.ORACLE,
      "POSTGRESQL_CSV" -> ApacheCSVFormat.POSTGRESQL_CSV,
      "POSTGRESQL_TEXT" -> ApacheCSVFormat.POSTGRESQL_TEXT,
      "INFORMIX_UNLOAD" -> ApacheCSVFormat.INFORMIX_UNLOAD,
      "INFORMIX_UNLOAD_CSV" -> ApacheCSVFormat.INFORMIX_UNLOAD_CSV,
      "MONGODB_CSV" -> ApacheCSVFormat.MONGODB_CSV,
      "MONGODB_TSV" -> ApacheCSVFormat.MONGODB_TSV
    )
    formats.foreach((n, f) => info(f"$n%-20s allowMissingColumnNames=${f.getAllowMissingColumnNames}"))

  "blank header column" should "show effect of the flag (requires setHeader)" in:
    def attempt(label: String, fmt: ApacheCSVFormat, input: String): Unit =
      try
        val recs = fmt.parse(StringReader(input)).getRecords.asScala.toList
        info(s"$label => OK ${recs.map(r => (0 until r.size).map(r.get).mkString("[", ",", "]"))}")
      catch case e: Exception => info(s"$label => ${e.getClass.getSimpleName}: ${e.getMessage}")

    val input = "a,,b\n1,2,3"
    attempt("RFC4180 no setHeader        ", ApacheCSVFormat.RFC4180, input)
    attempt("RFC4180 setHeader()         ", ApacheCSVFormat.RFC4180.builder().setHeader().get(), input)
    attempt("EXCEL   setHeader()         ", ApacheCSVFormat.EXCEL.builder().setHeader().get(), input)
    attempt("RFC4180 setHeader()+allow   ", ApacheCSVFormat.RFC4180.builder().setHeader().setAllowMissingColumnNames(true).get(), input)

end ApacheAllowMissingColumnNamesTest
