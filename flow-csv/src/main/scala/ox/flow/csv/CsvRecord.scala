package ox.flow.csv

import ox.flow.csv.format.internal.macros.fieldLabels

import scala.deriving.Mirror.ProductOf

/** Represents types other that user defined representation of data record that CSV file can contain */
sealed transparent trait CsvRecord

object CsvRecord:
  final case class HeaderRecord(names: String*) extends CsvRecord

  object HeaderRecord:
    inline def derived[T: ProductOf]: HeaderRecord = HeaderRecord(fieldLabels[T]*)

  final case class Comment(comment: String) extends CsvRecord
