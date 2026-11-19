package ox.flow.csv.format
import org.apache.commons.csv.QuoteMode as ApacheQuoteMode

/** @see
  *   [[org.apache.commons.csv.QuoteMode]]
  */
enum QuoteMode:
  /** @see
    *   [[org.apache.commons.csv.QuoteMode.ALL]]
    */
  case All

  /** @see
    *   [[org.apache.commons.csv.QuoteMode.ALL_NON_NULL]]
    */
  case AllNonNull

  /** @see
    *   [[org.apache.commons.csv.QuoteMode.MINIMAL]]
    */
  case Minimal

  /** Quotes all non-numeric fields.
    *
    * @see
    *   [[org.apache.commons.csv.QuoteMode.NON_NUMERIC]]
    */
  case NonNumeric

  /** @see
    *   [[org.apache.commons.csv.QuoteMode.NONE]]
    */
  case Never
end QuoteMode

object QuoteMode:
  private[csv] def fromApache(quoteMode: ApacheQuoteMode): QuoteMode = quoteMode match
    case ApacheQuoteMode.ALL          => All
    case ApacheQuoteMode.ALL_NON_NULL => AllNonNull
    case ApacheQuoteMode.MINIMAL      => Minimal
    case ApacheQuoteMode.NON_NUMERIC  => NonNumeric
    case ApacheQuoteMode.NONE         => Never

  extension (quoteMode: QuoteMode)
    private[csv] def toApache: ApacheQuoteMode = quoteMode match
      case All        => ApacheQuoteMode.ALL
      case AllNonNull => ApacheQuoteMode.ALL_NON_NULL
      case Minimal    => ApacheQuoteMode.MINIMAL
      case NonNumeric => ApacheQuoteMode.NON_NUMERIC
      case Never      => ApacheQuoteMode.NONE
end QuoteMode
