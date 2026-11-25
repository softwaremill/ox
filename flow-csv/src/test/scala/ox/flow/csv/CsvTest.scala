package ox.flow.csv

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.flow.Flow
import ox.flow.csv.codec.default.missingDataFallback.given
import ox.flow.csv.format.Format

class CsvTest extends AnyFlatSpec with Matchers:

  private def text(s: String) = Flow.fromValues(s)

  case class ThreeCols(a: String, b: String, c: String)

  "parse" should "parse with Default format" in:
    val result = text("a,b,c\nd,e,f").parse(Format.Default[ThreeCols])
    result should not be null

  it should "parse with Rfc4180 format" in:
    val result = text("a,b,c\nd,e,f").parse(Format.Rfc4180[ThreeCols])
    result should not be null

  it should "parse with Excel format" in:
    val result = text("a,b,c\nd,e,f").parse(Format.Excel[ThreeCols])
    result should not be null

  it should "produce an empty flow for empty input" in:
    val result = text("").parse(Format.Default[ThreeCols])
    result should not be null

end CsvTest
