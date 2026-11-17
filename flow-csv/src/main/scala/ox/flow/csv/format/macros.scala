package ox.flow.csv.format

import scala.compiletime.{constValue, erasedValue}
import scala.deriving.Mirror.ProductOf

object macros {
  private[ox] inline def parseRec[Tup <: Tuple](i: Int, buffer: Array[String]): Array[String] =
    inline erasedValue[Tup] match {
      case _: EmptyTuple => buffer
      case _: (h *: t) =>
        buffer(i) = constValue[h].asInstanceOf[String]
        parseRec[t](i + 1, buffer)
    }
  
  private[ox] inline def fieldLabels[T: ProductOf]: Array[String] =
    val mir = summon[ProductOf[T]]
    type MirroredLabels = mir.MirroredElemLabels
    val size = constValue[Tuple.Size[MirroredLabels]]
  
    parseRec[MirroredLabels](0, new Array[String](size))
  

}
