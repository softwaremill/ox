package ox.flow.csv.codec

/** Raised while reading or writing CSV. Sealed, so the cases can be matched exhaustively.
  *
  * Decoding runs per row inside a [[ox.flow.Flow]], so these propagate through the flow and are handled there - with `Flow` error handling,
  * or by catching around the run - rather than being returned per cell.
  */
sealed abstract class CsvException(message: String, cause: Throwable | Null = null) extends Exception(message, cause)
object CsvException:
  
  
  /** Thrown when parsed data don't comply with defined Format */
  sealed abstract class FormatViolationException(message: String) extends CsvException(message)
  
  object FormatViolationException:
    
    final case class MissingColumn(index: Int)
      extends FormatViolationException(s"Missing column at index $index: the format does not allow ragged rows")
  
    /** The cell at `index` was `null`, under a format that defines no null string*/
    final case class UnexpectedNull(index: Int)
      extends FormatViolationException(s"Unexpected null at column index $index: the format defines no null string")
  
    /** The row had more cells than the target type has fields. */
    final case class TooManyColumns(expected: Int, actual: Int)
      extends FormatViolationException(s"Too many columns: expected $expected, got $actual")
  
  end FormatViolationException
  
  /** Failed to decode field String into typed representation by [[CsvDecoder.decode]]
   */
  final class FieldDecodeException(val index: Int, val value: String, cause: Throwable)
    extends CsvException(s"Cannot decode column $index from '$value': ${cause.getMessage}", cause)
