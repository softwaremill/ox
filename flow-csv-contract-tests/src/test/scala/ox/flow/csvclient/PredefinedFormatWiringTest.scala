package ox.flow.csvclient

import org.scalatest.exceptions.TestFailedException
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import ox.flow.Flow
import ox.flow.csv.CsvRecord.HeaderRecord
import ox.flow.csv.format.{Format, PrinterFormat}

import scala.compiletime.testing.typeCheckErrors

/** What each predefined format demands of `T`, asserted at compiletime.
  *
  * A format's settings decide which typeclass every field must provide, so the same case class is accepted by one dialect and rejected by
  * another:
  *   - no null string: every field encodes through a plain `CsvEncoder`, which has no `Option` instance, so an `Option` field is rejected
  *   - ragged rows allowed: every field but the first needs a `MissingDataFallback`, `String` included - a missing cell is not an empty one,
  *     so it has no automatic instance either
  *   - `DisallowRaggedRows`: no `MissingDataFallback` is consulted, so a plain type is accepted without one
  *   - a null string: cells can arrive null, so every field needs a `NullFallback` and a `CsvEncoderWithNull`
  *
  * Grouped by format, with `compile` / `not compile` as a name prefix: the two halves of one dialect's contract read together. Each case is
  * written out per format rather than looped over, which the assertions require anyway - `assertCompiles` and `typeCheckErrors` are macros
  * and take a literal, and an `inline match` over a varargs `Seq` does not destructure, so a recursive helper over several snippets
  * silently checks none of them.
  *
  * A `compile` case calls the constructor outright: the test only compiles if the instances resolve. A `not compile` case asserts the
  * compiler's message, not merely that some error occurred - a rejection for the wrong reason is a passing test that proves nothing.
  *
  * The primitive fallbacks are imported per test rather than for the file: in scope everywhere, they supply the very instance the rejection
  * cases assert is absent, so nothing would be rejected.
  */
class PredefinedFormatWiringTest extends AnyWordSpec with Matchers:
  import PredefinedFormatWiringTest.*
  
  private def compiles(expr: => Any): Assertion = succeed

  inline private def compileErrorIncludes(inline code: String, shouldInclude: String): Unit =
    val errs = typeCheckErrors(code)
    errs should not be empty
    atLeast(1, errs.map(_.toString)) should include(shouldInclude)

  private val noMissingForInt = "No MissingDataFallback instance for Int"
  private val noMissingForString = "No MissingDataFallback instance for String"
  private val noNullFallbackForInt = "No NullData instance for Int"
  private val cannotEncodeOption = "no implicit values were found that match type ox.flow.csv.codec.CsvEncoder[Option["

  /** An `Option` field reaching the plain-encoder branch also gets a note under that message, pointing at the null string and
    * `CsvEncoderWithNull`. Asserted by [[compileErrorIncludes]], since the text sits below the first line.
    */
  private val suggestsNullString = "define the format with a nullString and provide a CsvEncoderWithNull"

  "Format.Rfc4180" should:

    "compile for an all-String type once MissingDataFallback is in scope" in:
      import ox.flow.csv.codec.default.missingDataFallback.given
      compiles(Format.Rfc4180[Ragged])

    "compile for a non-String tail field once MissingDataFallback is in scope" in:
      import ox.flow.csv.codec.default.missingDataFallback.given
      compiles(Format.Rfc4180[Plain])

    "compile for an Option field once the user supplies a CsvEncoder for it" in:
      import ox.flow.csv.codec.default.missingDataFallback.given
      given optIntEncoder: ox.flow.csv.codec.CsvEncoder[Option[Int]] with
        def encode(v: Option[Int]): String = v.fold("-")(_.toString)
      given optStringEncoder: ox.flow.csv.codec.CsvEncoder[Option[String]] with
        def encode(v: Option[String]): String = v.getOrElse("-")
      compiles(Format.Rfc4180[Nullable])

    "compile for a single-field type without MissingDataFallback in scope" in:
      compiles(Format.Rfc4180[SingleField])

    "not compile for an String tail field without MissingDataFallback in scope" in:
      compileErrorIncludes("""ox.flow.csv.format.Format.Rfc4180[ox.flow.csvclient.PredefinedFormatWiringTest.Ragged]""", noMissingForString)

    "not compile for a non-String tail field without MissingDataFallback in scope" in:
      compileErrorIncludes("""ox.flow.csv.format.Format.Rfc4180[ox.flow.csvclient.PredefinedFormatWiringTest.Plain]""", noMissingForInt)

    "not compile for an Option field, the plain CsvEncoder being summoned and Option having no instance" in:
      compileErrorIncludes("""ox.flow.csv.format.Format.Rfc4180[ox.flow.csvclient.PredefinedFormatWiringTest.Nullable]""", cannotEncodeOption)

    "contain note under compile error message that a nullString and CsvEncoderWithNull are how null gets written" in:
      compileErrorIncludes("""ox.flow.csv.format.Format.Rfc4180[ox.flow.csvclient.PredefinedFormatWiringTest.Nullable]""", suggestsNullString)

  "Format.Default" should:

    "compile for an all-String type once the primitive fallbacks are imported" in:
      import ox.flow.csv.codec.default.missingDataFallback.given
      Format.Default[Ragged] should not be null

    "compile for a non-String tail field once the primitive fallbacks are imported" in:
      import ox.flow.csv.codec.default.missingDataFallback.given
      Format.Default[Plain] should not be null

    "not compile for an all-String type with no fallbacks imported" in:
      rejectedBecause("""ox.flow.csv.format.Format.Default[ox.flow.csvclient.PredefinedFormatWiringTest.Ragged]""", noMissingForString)

    "not compile for a non-String tail field with no MissingDataFallback in scope" in:
      rejectedBecause("""ox.flow.csv.format.Format.Default[ox.flow.csvclient.PredefinedFormatWiringTest.Plain]""", noMissingForInt)

    "not compile for an Option field, which the plain CsvEncoder has no instance for" in:
      rejectedBecause("""ox.flow.csv.format.Format.Default[ox.flow.csvclient.PredefinedFormatWiringTest.Nullable]""", cannotEncodeOption)

  "Format.Excel" should:

    "compile for an all-String type once the primitive fallbacks are imported" in:
      import ox.flow.csv.codec.default.missingDataFallback.given
      Format.Excel[Ragged] should not be null

    "compile for a non-String tail field once the primitive fallbacks are imported" in:
      import ox.flow.csv.codec.default.missingDataFallback.given
      Format.Excel[Plain] should not be null

    "not compile for an all-String type with no fallbacks imported" in:
      rejectedBecause("""ox.flow.csv.format.Format.Excel[ox.flow.csvclient.PredefinedFormatWiringTest.Ragged]""", noMissingForString)

    "not compile for a non-String tail field with no MissingDataFallback in scope" in:
      rejectedBecause("""ox.flow.csv.format.Format.Excel[ox.flow.csvclient.PredefinedFormatWiringTest.Plain]""", noMissingForInt)

    "not compile for an Option field, which the plain CsvEncoder has no instance for" in:
      rejectedBecause("""ox.flow.csv.format.Format.Excel[ox.flow.csvclient.PredefinedFormatWiringTest.Nullable]""", cannotEncodeOption)

  "Format.ExcelStrict" should:

    "compile for an all-String type once the primitive fallbacks are imported" in:
      import ox.flow.csv.codec.default.missingDataFallback.given
      Format.ExcelStrict[Ragged] should not be null

    "compile for a non-String tail field once the primitive fallbacks are imported" in:
      import ox.flow.csv.codec.default.missingDataFallback.given
      Format.ExcelStrict[Plain] should not be null

    "not compile for an all-String type with no fallbacks imported" in:
      rejectedBecause("""ox.flow.csv.format.Format.ExcelStrict[ox.flow.csvclient.PredefinedFormatWiringTest.Ragged]""", noMissingForString)

    "not compile for a non-String tail field with no MissingDataFallback in scope" in:
      rejectedBecause("""ox.flow.csv.format.Format.ExcelStrict[ox.flow.csvclient.PredefinedFormatWiringTest.Plain]""", noMissingForInt)

    "not compile for an Option field, which the plain CsvEncoder has no instance for" in:
      rejectedBecause(
        """ox.flow.csv.format.Format.ExcelStrict[ox.flow.csvclient.PredefinedFormatWiringTest.Nullable]""",
        cannotEncodeOption
      )

  "Format.Tdf" should:

    "compile for an all-String type once the primitive fallbacks are imported" in:
      import ox.flow.csv.codec.default.missingDataFallback.given
      Format.Tdf[Ragged] should not be null

    "compile for a non-String tail field once the primitive fallbacks are imported" in:
      import ox.flow.csv.codec.default.missingDataFallback.given
      Format.Tdf[Plain] should not be null

    "not compile for an all-String type with no fallbacks imported" in:
      rejectedBecause("""ox.flow.csv.format.Format.Tdf[ox.flow.csvclient.PredefinedFormatWiringTest.Ragged]""", noMissingForString)

    "not compile for a non-String tail field with no MissingDataFallback in scope" in:
      rejectedBecause("""ox.flow.csv.format.Format.Tdf[ox.flow.csvclient.PredefinedFormatWiringTest.Plain]""", noMissingForInt)

    "not compile for an Option field, which the plain CsvEncoder has no instance for" in:
      rejectedBecause("""ox.flow.csv.format.Format.Tdf[ox.flow.csvclient.PredefinedFormatWiringTest.Nullable]""", cannotEncodeOption)

  "Format.Rfc4180Strict" should:
    // DisallowRaggedRows: a short row fails at runtime instead, so no MissingDataFallback is summoned and no import is needed.

    "compile for an all-String type with no fallbacks imported" in:
      Format.Rfc4180Strict[Ragged] should not be null

    "compile for a non-String tail field with no fallbacks imported" in:
      Format.Rfc4180Strict[Plain] should not be null

    "not compile for an Option field, dropping the missing-data demand not relaxing the encoder" in:
      rejectedBecause(
        """ox.flow.csv.format.Format.Rfc4180Strict[ox.flow.csvclient.PredefinedFormatWiringTest.Nullable]""",
        cannotEncodeOption
      )

  "Format.InformixUnload" should:

    "compile for a non-String tail field once the primitive fallbacks are imported" in:
      import ox.flow.csv.codec.default.missingDataFallback.given
      Format.InformixUnload[Plain] should not be null

    "not compile for a non-String tail field with no MissingDataFallback in scope" in:
      rejectedBecause("""ox.flow.csv.format.Format.InformixUnload[ox.flow.csvclient.PredefinedFormatWiringTest.Plain]""", noMissingForInt)

  "Format.InformixUnloadCsv" should:

    "compile for a non-String tail field once the primitive fallbacks are imported" in:
      import ox.flow.csv.codec.default.missingDataFallback.given
      Format.InformixUnloadCsv[Plain] should not be null

    "not compile for a non-String tail field with no MissingDataFallback in scope" in:
      rejectedBecause(
        """ox.flow.csv.format.Format.InformixUnloadCsv[ox.flow.csvclient.PredefinedFormatWiringTest.Plain]""",
        noMissingForInt
      )

  "Format.MongoDbCsv" should:
    // Printer only: no decoder is built, so no fallback is ever required and only the encoder constrains `T`.

    "compile for a plain type, since a printer needs no fallbacks" in:
      Format.MongoDbCsv[Plain] should not be null

    "not compile for an Option field, the encoder constraining it as for any other format" in:
      rejectedBecause(
        """ox.flow.csv.format.Format.MongoDbCsv[ox.flow.csvclient.PredefinedFormatWiringTest.Nullable]""",
        cannotEncodeOption
      )

  "Format.MongoDbTsv" should:

    "compile for a plain type, since a printer needs no fallbacks" in:
      Format.MongoDbTsv[Plain] should not be null

    "not compile for an Option field, the encoder constraining it as for any other format" in:
      rejectedBecause(
        """ox.flow.csv.format.Format.MongoDbTsv[ox.flow.csvclient.PredefinedFormatWiringTest.Nullable]""",
        cannotEncodeOption
      )

  // Formats defining a null string: cells can arrive null and be written null, so every field needs a NullFallback (decoding) and a
  // CsvEncoderWithNull (encoding).
  //
  // The two constrain `T` differently, because of their variance:
  //   - NullFallback is covariant, so a non-nullable field is rejected unless the primitive defaults are imported
  //   - CsvEncoderWithNull is contravariant, so `CsvEncoderWithNull[Int | Null]` *is* a `CsvEncoderWithNull[Int]` and a non-nullable field
  //     always encodes. That is sound: such a field never produces a null, so the format's null string simply goes unused for it.
  //
  // These formats allow ragged rows too, so a bare non-nullable type trips the missing-data demand first and the null demand only once that
  // one is satisfied. Both are asserted, so neither can regress unnoticed.

  "Format.MySql" should:

    "compile for a type whose fields are all nullable" in:
      Format.MySql[Nullable] should not be null

    "compile for a non-nullable field once the primitive fallbacks are imported" in:
      import ox.flow.csv.codec.default.all.given
      Format.MySql[Plain] should not be null

    "not compile for a non-nullable field with no fallbacks in scope, reporting the missing-data demand first" in:
      rejectedBecause("""ox.flow.csv.format.Format.MySql[ox.flow.csvclient.PredefinedFormatWiringTest.Plain]""", noMissingForInt)

    "not compile for a single-field type, the null demand applying to the first field as much as to a tail one" in:
      rejectedBecause("""ox.flow.csv.format.Format.MySql[ox.flow.csvclient.PredefinedFormatWiringTest.SingleField]""", noNullFallbackForInt)

    "not compile for a non-nullable field once the missing-data fallbacks are imported, now for the null demand" in:
      rejectedBecause(
        """{ import ox.flow.csv.codec.default.missingDataFallback.given; ox.flow.csv.format.Format.MySql[ox.flow.csvclient.PredefinedFormatWiringTest.Plain] }""",
        noNullFallbackForInt
      )

  "Format.Oracle" should:

    "compile for a type whose fields are all nullable" in:
      Format.Oracle[Nullable] should not be null

    "compile for a non-nullable field once the primitive fallbacks are imported" in:
      import ox.flow.csv.codec.default.all.given
      Format.Oracle[Plain] should not be null

    "not compile for a non-nullable field with no fallbacks in scope, reporting the missing-data demand first" in:
      rejectedBecause("""ox.flow.csv.format.Format.Oracle[ox.flow.csvclient.PredefinedFormatWiringTest.Plain]""", noMissingForInt)

    "not compile for a non-nullable field once the missing-data fallbacks are imported, now for the null demand" in:
      rejectedBecause(
        """{ import ox.flow.csv.codec.default.missingDataFallback.given; ox.flow.csv.format.Format.Oracle[ox.flow.csvclient.PredefinedFormatWiringTest.Plain] }""",
        noNullFallbackForInt
      )

  "Format.PostgreSqlCsv" should:

    "compile for a type whose fields are all nullable" in:
      Format.PostgreSqlCsv[Nullable] should not be null

    "compile for a non-nullable field once the primitive fallbacks are imported" in:
      import ox.flow.csv.codec.default.all.given
      Format.PostgreSqlCsv[Plain] should not be null

    "not compile for a non-nullable field with no fallbacks in scope, reporting the missing-data demand first" in:
      rejectedBecause("""ox.flow.csv.format.Format.PostgreSqlCsv[ox.flow.csvclient.PredefinedFormatWiringTest.Plain]""", noMissingForInt)

    "not compile for a non-nullable field once the missing-data fallbacks are imported, now for the null demand" in:
      rejectedBecause(
        """{ import ox.flow.csv.codec.default.missingDataFallback.given; ox.flow.csv.format.Format.PostgreSqlCsv[ox.flow.csvclient.PredefinedFormatWiringTest.Plain] }""",
        noNullFallbackForInt
      )

  "Format.PostgreSqlText" should:

    "compile for a type whose fields are all nullable" in:
      Format.PostgreSqlText[Nullable] should not be null

    "compile for a non-nullable field once the primitive fallbacks are imported" in:
      import ox.flow.csv.codec.default.all.given
      Format.PostgreSqlText[Plain] should not be null

    "not compile for a non-nullable field with no fallbacks in scope, reporting the missing-data demand first" in:
      rejectedBecause("""ox.flow.csv.format.Format.PostgreSqlText[ox.flow.csvclient.PredefinedFormatWiringTest.Plain]""", noMissingForInt)

    "not compile for a non-nullable field once the missing-data fallbacks are imported, now for the null demand" in:
      rejectedBecause(
        """{ import ox.flow.csv.codec.default.missingDataFallback.given; ox.flow.csv.format.Format.PostgreSqlText[ox.flow.csvclient.PredefinedFormatWiringTest.Plain] }""",
        noNullFallbackForInt
      )

  "the stream shape a format exposes" should:
    // Pinned by ascription: the shape is computed from the settings, so a change to either shows up here as a type error.

    "be a flow carrying either a header or a row, for an optional-header format" in:
      import ox.flow.csv.codec.default.missingDataFallback.given
      val f: Format[Flow[HeaderRecord | Plain]] = Format.Default[Plain]
      f should not be null

    "be a flow of rows alone, with no HeaderRecord in the stream, for a headerless format" in:
      val f: Format[Flow[Nullable]] = Format.MySql[Nullable]
      f should not be null

    "take the header beside the rows, for a printer-only format" in:
      val f: PrinterFormat[(HeaderRecord, Flow[Plain])] = Format.MongoDbCsv[Plain]
      f should not be null

    "expose no parser, for a printer-only format" in:
      rejectedBecause(
        """val f: ox.flow.csv.format.ParserFormat[Any] = ox.flow.csv.format.Format.MongoDbCsv[ox.flow.csvclient.PredefinedFormatWiringTest.Plain]""",
        "ox.flow.csv.format.PrinterFormat"
      )

end PredefinedFormatWiringTest

object PredefinedFormatWiringTest:
  /** Every field a String. A ragged format still needs a `MissingDataFallback[String]`: a missing cell is not an empty one. */
  case class Ragged(a: String, b: String)

  /** A non-String tail field: needs a `MissingDataFallback[Int]`, which only the primitive defaults supply. */
  case class Plain(a: String, b: Int)

  /** A single field, so there is no tail at all.
    *
    * The first field is never missing - a record always has at least one cell - so a ragged format summons a `MissingDataFallback` for
    * every field *after* the first, and here there are none. Such a type is therefore accepted with no fallback import, whatever the field
    * type, which no multi-field fixture can show.
    */
  case class SingleField(only: Int)

  /** Nullable fields: carry `NullFallback`, `MissingDataFallback` and `CsvEncoderWithNull` from the companions, with no import. */
  case class Nullable(a: Option[String], b: Option[Int])
