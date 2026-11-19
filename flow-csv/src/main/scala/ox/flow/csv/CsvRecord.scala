package ox.flow.csv

import ox.flow.csv.format.internal.macros.fieldLabels

import scala.deriving.Mirror.ProductOf

/** Represents types that CSV file can contain, other that user defined representation of data record*/
sealed transparent trait CsvRecord

object CsvRecord:
  /**Represents comment in csv file
   * @see todo BuilderDsl.setHeaderMode*/
  final case class HeaderRecord(names: String*) extends CsvRecord

  object HeaderRecord:
    /**Creates header record with names same as [[T]] field names
     * @see [[ProductOf.MirroredElemLabels]] */
    inline def derived[T: ProductOf]: HeaderRecord = HeaderRecord(fieldLabels[T]*)

  /**Represents comment in csv file
   * @see todo BuilderDsl.setCommentPrefix*/
  final case class Comment(comment: String) extends CsvRecord
