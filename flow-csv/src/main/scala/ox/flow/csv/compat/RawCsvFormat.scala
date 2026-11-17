package ox.flow.csv.compat

import org.apache.commons.csv.CSVFormat as ApacheCSVFormat

import scala.annotation.publicInBinary

/**
 * [[ApacheCSVFormat]] wrapper not to expose it to user code (even inlined)
  */
private[csv] final class RawCsvFormat (val underlying: ApacheCSVFormat)
