package ox.flow.csv
import ox.flow.csv.codec.CsvDecoder

/** Raised while reading or writing CSV. Sealed, so the cases can be matched exhaustively.
  *
  * Decoding runs per row inside a [[ox.flow.Flow]], so these propagate through the flow and are handled there - with `Flow` error handling,
  * or by catching around the run - rather than being returned per cell.
  */
sealed abstract class CsvException(message: String, cause: Throwable | Null = null) extends Exception(message, cause)
object CsvException:

  /**Row red by parsing backend was not possible to represent as typed representation specified by user*/
  sealed abstract class FormatViolationException(message: String, cause: Throwable | Null = null)
      extends CsvException(message, cause)

  object FormatViolationException:

    final case class MissingColumn(index: Int)
        extends FormatViolationException(s"Missing column at index $index: the format does not allow ragged rows")

    final case class UnexpectedNull(index: Int)
        extends FormatViolationException(s"Unexpected null at column index $index: the format defines no null string")

    final case class TooManyColumns(expected: Int, actual: Int)
        extends FormatViolationException(s"Too many columns: expected $expected, got $actual")

    /** Failed to decode field String into typed representation by [[CsvDecoder.decode]], which threw [[cause]]. */
    final class FieldDecodeException(val index: Int, val value: String, cause: Throwable)
        extends FormatViolationException(s"Cannot decode column $index from '$value': ${cause.getMessage}", cause)

  end FormatViolationException

  /** Parsing backend failed to parse a CSV text, it differs from [[FormatViolationException]] because it's not related to typed representation specified by user*/
  final case class MalformedCsvException(detail: String) extends CsvException(s"Malformed CSV: $detail")
end CsvException
