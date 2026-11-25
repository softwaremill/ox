package ox.flow.csv

import ox.flow.Flow
import ox.flow.csv.format.{ParserFormat, PrinterFormat}

/** Writes `f` as CSV text, one element of the resulting flow per record.
  *
  * The receiver is whatever shape the format prints, which its type states: a `Flow[T]` for a headerless format, a
  * `Flow[CsvRecord.HeaderRecord | T]` where a header is optional, or a `(CsvRecord.HeaderRecord, Flow[T])` where one is required - so a
  * format that needs a header cannot be handed a bare flow of rows.
  *
  * {{{
  * import ox.flow.csv.*
  * Flow.fromValues(Person("ann", 31)).print(Format.Rfc4180[Person])
  * }}}
  *
  * Encoding happens per record as the returned flow is run, so a failure surfaces there rather than from this call.
  *
  * @throws java.io.IOException
  *   if writing a record fails. It propagates as the writer threw it, rather than wrapped: `IOException` already says what went wrong. A
  *   failure of `f` itself propagates as it was thrown too.
  */
extension [T](f: T) def print(fmt: PrinterFormat[T]): Flow[String] = fmt.writeData(f)

/** Parses this flow of CSV text as the format's representation.
  *
  * The result's type is the one the format computes from its settings: `Flow[T]` where no header is possible, or
  * `Flow[CsvRecord.HeaderRecord | T]` where the first record may be one - so a headerless format never asks the caller to handle a header.
  * An element is a piece of text, not a line: records are split on the format's record separator alone, so elements are concatenated as
  * they arrive and a record may span several of them (or several records share one). Line-oriented input therefore has to carry its own
  * separators - `Flow.fromValues("name,age", "ann,31")` parses as the single record `name,ageann`.
  *
  * {{{
  * import ox.flow.csv.*
  * Flow.fromValues("name,age\r\nann,31\r\n").parse(Format.Rfc4180[Person])
  * }}}
  *
  * Decoding happens per row as the returned flow is run, so both exceptions are raised from the run rather than from this call, and are
  * handled with the flow's error handling or around it.
  *
  * @throws ox.flow.csv.codec.CsvException.FieldDecodeException
  *   if a [[ox.flow.csv.codec.CsvDecoder]] rejects a cell. It carries the column and the value, with the decoder's own exception as the
  *   cause.
  * @throws ox.flow.csv.codec.CsvException.FormatViolationException
  *   if a row does not fit the format: a missing column where ragged rows are disallowed, a `null` cell where the format defines no null
  *   string, or more cells than the target type has fields.
  * @throws ox.flow.csv.codec.CsvException.MalformedCsvException
  *   if the text is not well-formed CSV, so that no row can be read from it - an unterminated quote, say.
  * @throws java.io.IOException
  *   if reading the text fails. It propagates as the reader threw it, rather than wrapped: `IOException` already says what went wrong. A
  *   failure of the parsed flow propagates as it was thrown too, from the run.
  */
extension [T](f: Flow[String]) def parse(fmt: ParserFormat[T]): T = fmt.parseData(f)
