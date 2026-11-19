package ox.flow.csv.codec

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.flow.csv.codec.internal.{CsvRowDecoder, CsvRowEncoder}

import scala.compiletime.testing.typeCheckErrors
import scala.deriving.Mirror.ProductOf

class CsvRowCodecsTest extends AnyFlatSpec with Matchers:

  case class Custom(v: String)
  case class WithCustom(a: String, b: Custom)
  case class Plain(a: String, b: Int)
  case class WithOpt(a: String, b: Option[Int])

  "decoder without nullString, without ragged" should "decode a full row" in:
    val d = CsvRowDecoder[Plain](formatHasNullString = false, allowRaggedRows = false)
    d.decodeRow(Array("x", "7")) shouldBe Plain("x", 7)

  it should "fail on a short row" in:
    val d = CsvRowDecoder[Plain](formatHasNullString = false, allowRaggedRows = false)
    a[CsvRowDecoder.CsvDecodeException] should be thrownBy d.decodeRow(Array("x"))

  "first field" should "need no MissingDataFallback (it is never missing)" in:
    // String has no MissingDataFallback; only the trailing Option[Int] does
    val d = CsvRowDecoder[WithOpt](formatHasNullString = false, allowRaggedRows = true)
    d.decodeRow(Array("x")) shouldBe WithOpt("x", None)

  it should "still require fallbacks for later fields" in:
    val errs = typeCheckErrors(
      """CsvRowDecoder[Plain](formatHasNullString = false, allowRaggedRows = true)"""
    )
    info(if errs.isEmpty then "NO ERROR (unexpected)" else errs.map(_.message).mkString(" | ").take(140))
    errs should not be empty

  "decoder with ragged rows" should "use MissingDataFallback for absent trailing fields" in:
    val d = CsvRowDecoder[WithOpt](formatHasNullString = false, allowRaggedRows = true)
    d.decodeRow(Array("x")) shouldBe WithOpt("x", None)
    d.decodeRow(Array("x", "7")) shouldBe WithOpt("x", Some(7))

  "decoder with nullString" should "use NullFallback for null cells" in:
    // `a: String` needs a NullFallback too: a null cell can land on any field, and String has no automatic instance
    import ox.flow.csv.codec.default.nullFallback.given
    val d = CsvRowDecoder[WithOpt](formatHasNullString = true, allowRaggedRows = false)
    d.decodeRow(Array("x", null)) shouldBe WithOpt("x", None)
    d.decodeRow(Array("x", "7")) shouldBe WithOpt("x", Some(7))

  "encoder without nullString" should "use plain CsvEncoder" in:
    val e = CsvRowEncoder[Plain](formatHasNullString = false)
    e.encodeRow(Plain("x", 7)) shouldBe Array("x", "7")

  "encoder with nullString" should "prefer CsvEncoderWithNull so None becomes null" in:
    val e = CsvRowEncoder[WithOpt](formatHasNullString = true)
    e.encodeRow(WithOpt("x", Some(7))) shouldBe Array("x", "7")
    e.encodeRow(WithOpt("x", None)) shouldBe Array("x", null)

  "compile-time requirement" should "reject missing MissingDataFallback for ragged rows" in:
    val errs = typeCheckErrors(
      """CsvRowDecoder[Plain](formatHasNullString = false, allowRaggedRows = true)"""
    )
    info(if errs.isEmpty then "NO ERROR (unexpected)" else errs.map(_.message).mkString(" | ").take(200))
    errs should not be empty

  it should "reject missing NullFallback when format has a null string" in:
    val errs = typeCheckErrors(
      """CsvRowDecoder[Plain](formatHasNullString = true, allowRaggedRows = false)"""
    )
    info(if errs.isEmpty then "NO ERROR (unexpected)" else errs.map(_.message).mkString(" | ").take(200))
    errs should not be empty

  "encoder without a null string" should "reject a field that can only encode to null" in:
    // Option[Int] has a CsvEncoderWithNull but no plain CsvEncoder: it could emit a null the format
    // cannot represent, so it must not compile
    val errs = typeCheckErrors("""CsvRowEncoder[WithOpt](formatHasNullString = false)""")
    info(if errs.isEmpty then "NO ERROR (unexpected)" else errs.map(_.message).mkString(" | ").take(140))
    errs should not be empty

  "encoder with a null string" should "accept types that cannot hold a null" in:
    // String and Int have their own CsvEncoderWithNull: they encode as themselves, never as null
    CsvRowEncoder[Plain](formatHasNullString = true).encodeRow(Plain("x", 7)) shouldBe Array("x", "7")

  it should "accept a nullable field" in:
    CsvRowEncoder[WithOpt](formatHasNullString = true).encodeRow(WithOpt("x", None)) shouldBe Array("x", null)

  it should "reject a field whose type says nothing about nulls" in:
    val errs = typeCheckErrors("""CsvRowEncoder[WithCustom](formatHasNullString = true)""")
    info(if errs.isEmpty then "NO ERROR (unexpected)" else errs.map(_.message).mkString(" | ").take(140))
    errs should not be empty

  "a null from a format with no null string" should "warn once, naming the record" in:
    // a hand-written encoder for a nullable type: allowed, but the cell cannot read back as a null
    given CsvEncoder[Custom | Null] = v => if v == null then null else v.nn.v
    val enc = CsvRowEncoder[WithCustom](formatHasNullString = false)
    val err = java.io.ByteArrayOutputStream()
    val row = Console.withErr(err)(enc.encodeRow(WithCustom("x", null)))
    row shouldBe Array("x", null)
    info(err.toString.trim)

  it should "not warn when the format has a null string" in:
    val err = java.io.ByteArrayOutputStream()
    Console.withErr(err)(CsvRowEncoder[WithOpt](formatHasNullString = true).encodeRow(WithOpt("x", None)))
    err.toString shouldBe empty

end CsvRowCodecsTest
