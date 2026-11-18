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
