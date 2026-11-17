package ox.flow.csv

import ox.flow.csv.format.internal.macros.fieldLabels

import scala.deriving.Mirror.ProductOf


trait CsvRecord

object CsvRecord:
  case class HeaderRecord(names: String*) extends CsvRecord

  object HeaderRecord:
    inline def derived[T: ProductOf]: HeaderRecord = HeaderRecord(fieldLabels[T] *)

  case class Comment(comment:String) extends CsvRecord