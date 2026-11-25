package ox.flow.csv

import ox.flow.Flow
import ox.flow.csv.codec.default.missingDataFallback.given
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.flow.csv.format.Format

class RoundTripTest extends AnyFlatSpec with Matchers:

  case class Row(a: String, b: String)

  "a no-header format" should "write rows" in:
    val out = Format
      .InformixUnload[Row]
      .writeData(Flow.fromValues(Row("a", "b"), Row("c", "d")))
      .runToList()
    info(out.map(_.replace("\n", "\\n")).mkString(" | "))

  it should "read them back" in:
    val fmt = Format.InformixUnload[Row]
    val text = fmt.writeData(Flow.fromValues(Row("a", "b"), Row("c", "d"))).runToList().mkString
    val back = fmt.parseData(Flow.fromValues(text)).runToList()
    info(s"parsed: $back")
    back shouldBe List(Row("a", "b"), Row("c", "d"))

  "a header format" should "round-trip data rows" in:
    val fmt = Format.Rfc4180[Row]
    val text = fmt.writeData(Flow.fromValues(Row("a", "b"), Row("c", "d"))).runToList().mkString
    info(s"written: ${text.replace("\r", "\\r").replace("\n", "\\n")}")
    val back = fmt.parseData(Flow.fromValues(text)).runToList()
    info(s"parsed: $back")
end RoundTripTest
