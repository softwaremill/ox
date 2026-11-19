package ox.flow.csvclient

import org.scalatest.exceptions.TestFailedException
import org.scalatest.Assertion
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

  "Format.Default" should:

    "compile for an all-String type once MissingDataFallback is in scope" in:
      import ox.flow.csv.codec.default.missingDataFallback.given
      compiles(Format.Default[Ragged])

    "compile for a non-String tail field once MissingDataFallback is in scope" in:
      import ox.flow.csv.codec.default.missingDataFallback.given
      compiles(Format.Default[Plain])

    "compile for a single-field type without MissingDataFallback in scope" in:
      compiles(Format.Default[SingleField])

    "compile for an Option field once the user supplies a CsvEncoder for it" in:
      import ox.flow.csv.codec.default.missingDataFallback.given
      given optIntEncoder: ox.flow.csv.codec.CsvEncoder[Option[Int]] with
        def encode(v: Option[Int]): String = v.fold("-")(_.toString)
      given optStringEncoder: ox.flow.csv.codec.CsvEncoder[Option[String]] with
        def encode(v: Option[String]): String = v.getOrElse("-")
      compiles(Format.Default[Nullable])

    "not compile for an String tail field without MissingDataFallback in scope" in:
      compileErrorIncludes("""ox.flow.csv.format.Format.Default[ox.flow.csvclient.PredefinedFormatWiringTest.Ragged]""", noMissingForString)

    "not compile for a non-String tail field without MissingDataFallback in scope" in:
      compileErrorIncludes("""ox.flow.csv.format.Format.Default[ox.flow.csvclient.PredefinedFormatWiringTest.Plain]""", noMissingForInt)

    "not compile for an Option field, the plain CsvEncoder being summoned and Option having no instance" in:
      compileErrorIncludes("""ox.flow.csv.format.Format.Default[ox.flow.csvclient.PredefinedFormatWiringTest.Nullable]""", cannotEncodeOption)

    "contain note under compile error message that a nullString and CsvEncoderWithNull are how null gets written" in:
      compileErrorIncludes("""ox.flow.csv.format.Format.Default[ox.flow.csvclient.PredefinedFormatWiringTest.Nullable]""", suggestsNullString)

  "Format.DefaultStrict" should:

    "compile for an all-String type without MissingDataFallback in scope, none being summoned" in:
      compiles(Format.DefaultStrict[Ragged])

    "compile for a non-String tail field without MissingDataFallback in scope, none being summoned" in:
      compiles(Format.DefaultStrict[Plain])

    "compile for a single-field type without MissingDataFallback in scope" in:
      compiles(Format.DefaultStrict[SingleField])

    "not compile for an Option field, the plain CsvEncoder being summoned and Option having no instance" in:
      compileErrorIncludes("""ox.flow.csv.format.Format.DefaultStrict[ox.flow.csvclient.PredefinedFormatWiringTest.Nullable]""", cannotEncodeOption)

    "contain note under compile error message that a nullString and CsvEncoderWithNull are how null gets written" in:
      compileErrorIncludes("""ox.flow.csv.format.Format.DefaultStrict[ox.flow.csvclient.PredefinedFormatWiringTest.Nullable]""", suggestsNullString)

  "Format.Rfc4180" should:

    "compile for an all-String type once MissingDataFallback is in scope" in:
      import ox.flow.csv.codec.default.missingDataFallback.given
      compiles(Format.Rfc4180[Ragged])

    "compile for a non-String tail field once MissingDataFallback is in scope" in:
      import ox.flow.csv.codec.default.missingDataFallback.given
      compiles(Format.Rfc4180[Plain])

    "compile for a single-field type without MissingDataFallback in scope" in:
      compiles(Format.Rfc4180[SingleField])

    "compile for an Option field once the user supplies a CsvEncoder for it" in:
      import ox.flow.csv.codec.default.missingDataFallback.given
      given optIntEncoder: ox.flow.csv.codec.CsvEncoder[Option[Int]] with
        def encode(v: Option[Int]): String = v.fold("-")(_.toString)
      given optStringEncoder: ox.flow.csv.codec.CsvEncoder[Option[String]] with
        def encode(v: Option[String]): String = v.getOrElse("-")
      compiles(Format.Rfc4180[Nullable])

    "not compile for an String tail field without MissingDataFallback in scope" in:
      compileErrorIncludes("""ox.flow.csv.format.Format.Rfc4180[ox.flow.csvclient.PredefinedFormatWiringTest.Ragged]""", noMissingForString)

    "not compile for a non-String tail field without MissingDataFallback in scope" in:
      compileErrorIncludes("""ox.flow.csv.format.Format.Rfc4180[ox.flow.csvclient.PredefinedFormatWiringTest.Plain]""", noMissingForInt)

    "not compile for an Option field, the plain CsvEncoder being summoned and Option having no instance" in:
      compileErrorIncludes("""ox.flow.csv.format.Format.Rfc4180[ox.flow.csvclient.PredefinedFormatWiringTest.Nullable]""", cannotEncodeOption)

    "contain note under compile error message that a nullString and CsvEncoderWithNull are how null gets written" in:
      compileErrorIncludes("""ox.flow.csv.format.Format.Rfc4180[ox.flow.csvclient.PredefinedFormatWiringTest.Nullable]""", suggestsNullString)

  "Format.Rfc4180Strict" should:

    "compile for an all-String type without MissingDataFallback in scope, none being summoned" in:
      compiles(Format.Rfc4180Strict[Ragged])

    "compile for a non-String tail field without MissingDataFallback in scope, none being summoned" in:
      compiles(Format.Rfc4180Strict[Plain])

    "compile for a single-field type without MissingDataFallback in scope" in:
      compiles(Format.Rfc4180Strict[SingleField])

    "not compile for an Option field, the plain CsvEncoder being summoned and Option having no instance" in:
      compileErrorIncludes("""ox.flow.csv.format.Format.Rfc4180Strict[ox.flow.csvclient.PredefinedFormatWiringTest.Nullable]""", cannotEncodeOption)

    "contain note under compile error message that a nullString and CsvEncoderWithNull are how null gets written" in:
      compileErrorIncludes("""ox.flow.csv.format.Format.Rfc4180Strict[ox.flow.csvclient.PredefinedFormatWiringTest.Nullable]""", suggestsNullString)

  "Format.Excel" should:

    "compile for an all-String type once MissingDataFallback is in scope" in:
      import ox.flow.csv.codec.default.missingDataFallback.given
      compiles(Format.Excel[Ragged])

    "compile for a non-String tail field once MissingDataFallback is in scope" in:
      import ox.flow.csv.codec.default.missingDataFallback.given
      compiles(Format.Excel[Plain])

    "compile for a single-field type without MissingDataFallback in scope" in:
      compiles(Format.Excel[SingleField])

    "compile for an Option field once the user supplies a CsvEncoder for it" in:
      import ox.flow.csv.codec.default.missingDataFallback.given
      given optIntEncoder: ox.flow.csv.codec.CsvEncoder[Option[Int]] with
        def encode(v: Option[Int]): String = v.fold("-")(_.toString)
      given optStringEncoder: ox.flow.csv.codec.CsvEncoder[Option[String]] with
        def encode(v: Option[String]): String = v.getOrElse("-")
      compiles(Format.Excel[Nullable])

    "not compile for an String tail field without MissingDataFallback in scope" in:
      compileErrorIncludes("""ox.flow.csv.format.Format.Excel[ox.flow.csvclient.PredefinedFormatWiringTest.Ragged]""", noMissingForString)

    "not compile for a non-String tail field without MissingDataFallback in scope" in:
      compileErrorIncludes("""ox.flow.csv.format.Format.Excel[ox.flow.csvclient.PredefinedFormatWiringTest.Plain]""", noMissingForInt)

    "not compile for an Option field, the plain CsvEncoder being summoned and Option having no instance" in:
      compileErrorIncludes("""ox.flow.csv.format.Format.Excel[ox.flow.csvclient.PredefinedFormatWiringTest.Nullable]""", cannotEncodeOption)

    "contain note under compile error message that a nullString and CsvEncoderWithNull are how null gets written" in:
      compileErrorIncludes("""ox.flow.csv.format.Format.Excel[ox.flow.csvclient.PredefinedFormatWiringTest.Nullable]""", suggestsNullString)

  "Format.ExcelStrict" should:

    "compile for an all-String type without MissingDataFallback in scope, none being summoned" in:
      compiles(Format.ExcelStrict[Ragged])

    "compile for a non-String tail field without MissingDataFallback in scope, none being summoned" in:
      compiles(Format.ExcelStrict[Plain])

    "compile for a single-field type without MissingDataFallback in scope" in:
      compiles(Format.ExcelStrict[SingleField])

    "not compile for an Option field, the plain CsvEncoder being summoned and Option having no instance" in:
      compileErrorIncludes("""ox.flow.csv.format.Format.ExcelStrict[ox.flow.csvclient.PredefinedFormatWiringTest.Nullable]""", cannotEncodeOption)

    "contain note under compile error message that a nullString and CsvEncoderWithNull are how null gets written" in:
      compileErrorIncludes("""ox.flow.csv.format.Format.ExcelStrict[ox.flow.csvclient.PredefinedFormatWiringTest.Nullable]""", suggestsNullString)

  "Format.Tdf" should:

    "compile for an all-String type once MissingDataFallback is in scope" in:
      import ox.flow.csv.codec.default.missingDataFallback.given
      compiles(Format.Tdf[Ragged])

    "compile for a non-String tail field once MissingDataFallback is in scope" in:
      import ox.flow.csv.codec.default.missingDataFallback.given
      compiles(Format.Tdf[Plain])

    "compile for a single-field type without MissingDataFallback in scope" in:
      compiles(Format.Tdf[SingleField])

    "compile for an Option field once the user supplies a CsvEncoder for it" in:
      import ox.flow.csv.codec.default.missingDataFallback.given
      given optIntEncoder: ox.flow.csv.codec.CsvEncoder[Option[Int]] with
        def encode(v: Option[Int]): String = v.fold("-")(_.toString)
      given optStringEncoder: ox.flow.csv.codec.CsvEncoder[Option[String]] with
        def encode(v: Option[String]): String = v.getOrElse("-")
      compiles(Format.Tdf[Nullable])

    "not compile for an String tail field without MissingDataFallback in scope" in:
      compileErrorIncludes("""ox.flow.csv.format.Format.Tdf[ox.flow.csvclient.PredefinedFormatWiringTest.Ragged]""", noMissingForString)

    "not compile for a non-String tail field without MissingDataFallback in scope" in:
      compileErrorIncludes("""ox.flow.csv.format.Format.Tdf[ox.flow.csvclient.PredefinedFormatWiringTest.Plain]""", noMissingForInt)

    "not compile for an Option field, the plain CsvEncoder being summoned and Option having no instance" in:
      compileErrorIncludes("""ox.flow.csv.format.Format.Tdf[ox.flow.csvclient.PredefinedFormatWiringTest.Nullable]""", cannotEncodeOption)

    "contain note under compile error message that a nullString and CsvEncoderWithNull are how null gets written" in:
      compileErrorIncludes("""ox.flow.csv.format.Format.Tdf[ox.flow.csvclient.PredefinedFormatWiringTest.Nullable]""", suggestsNullString)

  "Format.TdfStrict" should:

    "compile for an all-String type without MissingDataFallback in scope, none being summoned" in:
      compiles(Format.TdfStrict[Ragged])

    "compile for a non-String tail field without MissingDataFallback in scope, none being summoned" in:
      compiles(Format.TdfStrict[Plain])

    "compile for a single-field type without MissingDataFallback in scope" in:
      compiles(Format.TdfStrict[SingleField])

    "not compile for an Option field, the plain CsvEncoder being summoned and Option having no instance" in:
      compileErrorIncludes("""ox.flow.csv.format.Format.TdfStrict[ox.flow.csvclient.PredefinedFormatWiringTest.Nullable]""", cannotEncodeOption)

    "contain note under compile error message that a nullString and CsvEncoderWithNull are how null gets written" in:
      compileErrorIncludes("""ox.flow.csv.format.Format.TdfStrict[ox.flow.csvclient.PredefinedFormatWiringTest.Nullable]""", suggestsNullString)

  "Format.InformixUnload" should:

    "compile for an all-String type without MissingDataFallback in scope, none being summoned" in:
      compiles(Format.InformixUnload[Ragged])

    "compile for a non-String tail field without MissingDataFallback in scope, none being summoned" in:
      compiles(Format.InformixUnload[Plain])

    "compile for a single-field type without MissingDataFallback in scope" in:
      compiles(Format.InformixUnload[SingleField])

    "not compile for an Option field, the plain CsvEncoder being summoned and Option having no instance" in:
      compileErrorIncludes("""ox.flow.csv.format.Format.InformixUnload[ox.flow.csvclient.PredefinedFormatWiringTest.Nullable]""", cannotEncodeOption)

    "contain note under compile error message that a nullString and CsvEncoderWithNull are how null gets written" in:
      compileErrorIncludes("""ox.flow.csv.format.Format.InformixUnload[ox.flow.csvclient.PredefinedFormatWiringTest.Nullable]""", suggestsNullString)

  "Format.InformixUnloadCsv" should:

    "compile for an all-String type without MissingDataFallback in scope, none being summoned" in:
      compiles(Format.InformixUnloadCsv[Ragged])

    "compile for a non-String tail field without MissingDataFallback in scope, none being summoned" in:
      compiles(Format.InformixUnloadCsv[Plain])

    "compile for a single-field type without MissingDataFallback in scope" in:
      compiles(Format.InformixUnloadCsv[SingleField])

    "not compile for an Option field, the plain CsvEncoder being summoned and Option having no instance" in:
      compileErrorIncludes("""ox.flow.csv.format.Format.InformixUnloadCsv[ox.flow.csvclient.PredefinedFormatWiringTest.Nullable]""", cannotEncodeOption)

    "contain note under compile error message that a nullString and CsvEncoderWithNull are how null gets written" in:
      compileErrorIncludes("""ox.flow.csv.format.Format.InformixUnloadCsv[ox.flow.csvclient.PredefinedFormatWiringTest.Nullable]""", suggestsNullString)

  "Format.MongoDbCsv" should:

    "compile for an all-String type without MissingDataFallback in scope, a printer summoning none" in:
      compiles(Format.MongoDbCsv[Ragged])

    "compile for a non-String tail field without MissingDataFallback in scope, a printer summoning none" in:
      compiles(Format.MongoDbCsv[Plain])

    "compile for a single-field type without MissingDataFallback in scope" in:
      compiles(Format.MongoDbCsv[SingleField])

    "not compile for an Option field, the plain CsvEncoder being summoned and Option having no instance" in:
      compileErrorIncludes("""ox.flow.csv.format.Format.MongoDbCsv[ox.flow.csvclient.PredefinedFormatWiringTest.Nullable]""", cannotEncodeOption)

    "contain note under compile error message that a nullString and CsvEncoderWithNull are how null gets written" in:
      compileErrorIncludes("""ox.flow.csv.format.Format.MongoDbCsv[ox.flow.csvclient.PredefinedFormatWiringTest.Nullable]""", suggestsNullString)

  "Format.MongoDbTsv" should:

    "compile for an all-String type without MissingDataFallback in scope, a printer summoning none" in:
      compiles(Format.MongoDbTsv[Ragged])

    "compile for a non-String tail field without MissingDataFallback in scope, a printer summoning none" in:
      compiles(Format.MongoDbTsv[Plain])

    "compile for a single-field type without MissingDataFallback in scope" in:
      compiles(Format.MongoDbTsv[SingleField])

    "not compile for an Option field, the plain CsvEncoder being summoned and Option having no instance" in:
      compileErrorIncludes("""ox.flow.csv.format.Format.MongoDbTsv[ox.flow.csvclient.PredefinedFormatWiringTest.Nullable]""", cannotEncodeOption)

    "contain note under compile error message that a nullString and CsvEncoderWithNull are how null gets written" in:
      compileErrorIncludes("""ox.flow.csv.format.Format.MongoDbTsv[ox.flow.csvclient.PredefinedFormatWiringTest.Nullable]""", suggestsNullString)

  "Format.MySql" should:

    "compile for a type whose fields are all nullable" in:
      compiles(Format.MySql[Nullable])

    "compile for a non-nullable field once NullFallback is in scope and no MissingFallback in scope" in:
      import ox.flow.csv.codec.default.nullFallback.given
      compiles(Format.MySql[Plain])

    "compile for a single-field type once NullFallback is in scope and no MissingFallback in scope" in:
      import ox.flow.csv.codec.default.nullFallback.given
      compiles(Format.MySql[SingleField])

    "not compile for a non-nullable field without NullFallback in scope" in:
      compileErrorIncludes("""ox.flow.csv.format.Format.MySql[ox.flow.csvclient.PredefinedFormatWiringTest.Plain]""", noNullFallbackForInt)

    "not compile for a single-field type without NullFallback in scope, the first field needing one too" in:
      compileErrorIncludes("""ox.flow.csv.format.Format.MySql[ox.flow.csvclient.PredefinedFormatWiringTest.SingleField]""", noNullFallbackForInt)

  "Format.Oracle" should:

    "compile for a type whose fields are all nullable" in:
      compiles(Format.Oracle[Nullable])

    "compile for a non-nullable field once NullFallback is in scope and no MissingFallback in scope" in:
      import ox.flow.csv.codec.default.nullFallback.given
      compiles(Format.Oracle[Plain])

    "compile for a single-field type once NullFallback is in scope and no MissingFallback in scope" in:
      import ox.flow.csv.codec.default.nullFallback.given
      compiles(Format.Oracle[SingleField])

    "not compile for a non-nullable field without NullFallback in scope" in:
      compileErrorIncludes("""ox.flow.csv.format.Format.Oracle[ox.flow.csvclient.PredefinedFormatWiringTest.Plain]""", noNullFallbackForInt)

    "not compile for a single-field type without NullFallback in scope, the first field needing one too" in:
      compileErrorIncludes("""ox.flow.csv.format.Format.Oracle[ox.flow.csvclient.PredefinedFormatWiringTest.SingleField]""", noNullFallbackForInt)

  "Format.PostgreSqlCsv" should:

    "compile for a type whose fields are all nullable" in:
      compiles(Format.PostgreSqlCsv[Nullable])

    "compile for a non-nullable field once NullFallback is in scope and no MissingFallback in scope" in:
      import ox.flow.csv.codec.default.nullFallback.given
      compiles(Format.PostgreSqlCsv[Plain])

    "compile for a single-field type once NullFallback is in scope and no MissingFallback in scope" in:
      import ox.flow.csv.codec.default.nullFallback.given
      compiles(Format.PostgreSqlCsv[SingleField])

    "not compile for a non-nullable field without NullFallback in scope" in:
      compileErrorIncludes("""ox.flow.csv.format.Format.PostgreSqlCsv[ox.flow.csvclient.PredefinedFormatWiringTest.Plain]""", noNullFallbackForInt)

    "not compile for a single-field type without NullFallback in scope, the first field needing one too" in:
      compileErrorIncludes("""ox.flow.csv.format.Format.PostgreSqlCsv[ox.flow.csvclient.PredefinedFormatWiringTest.SingleField]""", noNullFallbackForInt)

  "Format.PostgreSqlText" should:

    "compile for a type whose fields are all nullable" in:
      compiles(Format.PostgreSqlText[Nullable])

    "compile for a non-nullable field once NullFallback is in scope and no MissingFallback in scope" in:
      import ox.flow.csv.codec.default.nullFallback.given
      compiles(Format.PostgreSqlText[Plain])

    "compile for a single-field type once NullFallback is in scope and no MissingFallback in scope" in:
      import ox.flow.csv.codec.default.nullFallback.given
      compiles(Format.PostgreSqlText[SingleField])

    "not compile for a non-nullable field without NullFallback in scope" in:
      compileErrorIncludes("""ox.flow.csv.format.Format.PostgreSqlText[ox.flow.csvclient.PredefinedFormatWiringTest.Plain]""", noNullFallbackForInt)

    "not compile for a single-field type without NullFallback in scope, the first field needing one too" in:
      compileErrorIncludes("""ox.flow.csv.format.Format.PostgreSqlText[ox.flow.csvclient.PredefinedFormatWiringTest.SingleField]""", noNullFallbackForInt)

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
