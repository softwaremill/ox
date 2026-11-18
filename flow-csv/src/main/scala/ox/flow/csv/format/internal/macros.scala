package ox.flow.csv.format.internal

import scala.annotation.publicInBinary
import scala.compiletime.{constValue, erasedValue}
import scala.deriving.Mirror.ProductOf
import scala.quoted.{Expr, Quotes, Type, quotes}

@publicInBinary
private[ox] object macros:

  transparent inline def isSubtypeOf[Sub, Super]: Boolean = ${ isSubtypeOfImpl[Sub, Super] }

  def isSubtypeOfImpl[Sub: Type, Super: Type](using Quotes): Expr[Boolean] =
    import quotes.reflect.TypeRepr
    if TypeRepr.of[Sub] <:< TypeRepr.of[Super] then Expr(true) else Expr(false)

  /** Summons a `CsvEncoder[H]`, reporting a failed search for an `Option` field with a note appended.
    *
    * A format with no null string encodes every field through a plain `CsvEncoder`, which has no `Option` instance - the usual reason this
    * fails. Rather than replace the compiler's message, the search failure's own `explanation` is re-emitted with the note under it, so the
    * error still names the exact type it could not find. Only an `Option` field gets the note; anything else is reported unchanged.
    */
  inline def summonEncoderOrExplain[H]: ox.flow.csv.codec.CsvEncoder[H] = ${ summonEncoderOrExplainImpl[H] }

  def summonEncoderOrExplainImpl[H: Type](using Quotes): Expr[ox.flow.csv.codec.CsvEncoder[H]] =
    import quotes.reflect.*
    val encoderType = TypeRepr.of[ox.flow.csv.codec.CsvEncoder[H]]

    Implicits.search(encoderType) match
      case s: ImplicitSearchSuccess => s.tree.asExprOf[ox.flow.csv.codec.CsvEncoder[H]]
      case f: ImplicitSearchFailure =>
        val note =
          if TypeRepr.of[H] <:< TypeRepr.of[Option[Any]] then //explicit note to guide user to proper usage of API
            "\n\nNote: If you wish to write null, define the format with a nullString and provide a CsvEncoderWithNull."
          else ""
        report.errorAndAbort(f.explanation + note)
    end match
  end summonEncoderOrExplainImpl

  private inline def parseRec[Tup <: Tuple](i: Int, buffer: Array[String]): Array[String] =
    inline erasedValue[Tup] match
      case _: EmptyTuple => buffer
      case _: (h *: t)   =>
        buffer(i) = constValue[h].asInstanceOf[String]
        parseRec[t](i + 1, buffer)

  inline def fieldLabels[T: ProductOf]: Array[String] =
    val mir = summon[ProductOf[T]]
    type MirroredLabels = mir.MirroredElemLabels
    val size = constValue[Tuple.Size[MirroredLabels]]

    parseRec[MirroredLabels](0, new Array[String](size))
end macros
