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

  /** The text is not well-formed CSV, so no row could be read from it.
    *
    * Raised before a row is ever formed - an unterminated quote, a stray character after a closing quote, input ending inside an escape
    * sequence - as opposed to a [[FormatViolationException]], which is a well-formed row that does not fit the target type. `message` is
    * the parser's own description, and names the line it gave up on where it knows it.
    */
  final case class MalformedCsvException(detail: String)
    extends CsvException(s"Malformed CSV: $detail")

