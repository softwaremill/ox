package ox.flow.csvclient

import org.apache.commons.csv.CSVFormat as ApacheCSVFormat
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ApacheTrailingDelimiterFormatsTest extends AnyFlatSpec with Matchers:

  "all predefined formats" should "show trailingDelimiter setting" in:
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
    formats.foreach { (name, fmt) =>
      info(s"$name: trailingDelimiter=${fmt.getTrailingDelimiter}")
    }

end ApacheTrailingDelimiterFormatsTest
