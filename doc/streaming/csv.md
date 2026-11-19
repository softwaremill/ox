# Flows: CSV

Dependency:

```scala
"com.softwaremill.ox" %% "flow-csv" % "@VERSION@"
```

Ox can parse a `Flow[String]` of CSV text into rows, and render rows back into text. A row is a list of fields.
Parsing and rendering are done by [Apache Commons CSV](https://commons.apache.org/proper/commons-csv), and the dialect
is described using its `CSVFormat`: `CSVFormat.DEFAULT` by default, or e.g. `CSVFormat.EXCEL`, `CSVFormat.TDF`,
`CSVFormat.MYSQL`, `CSVFormat.POSTGRESQL_CSV`, or a customised one (delimiter, quote and escape characters, etc.).

The operations work on text, so they don't depend on the encoding: the text can be split into strings anywhere, and
bytes are decoded and encoded separately, e.g. with `decodeStringUtf8` and `encodeUtf8`. `decodeStringUtf8` drops a
UTF-8 byte-order mark; a byte-order mark remaining in the text would become a part of the first field.

## flow.parseCsv

A header row is emitted like any other; use `.drop(1)` to skip it. Malformed
input, such as an unterminated quoted field, fails the flow with an `UncheckedIOException`.

Reading the input creates an asynchronous boundary, so strings are consumed ahead of downstream demand.

```scala mdoc:compile-only
import ox.flow.Flow
import ox.flow.csv.*
import java.nio.file.Paths

Flow
  .fromFile(Paths.get("people.csv"))
  .decodeStringUtf8
  .parseCsv()
  .drop(1)
  .runForeach(println)
```

A different dialect:

```scala mdoc:compile-only
import org.apache.commons.csv.CSVFormat
import ox.flow.Flow
import ox.flow.csv.*
import java.nio.file.Paths

Flow
  .fromFile(Paths.get("people.csv"))
  .decodeStringUtf8
  .parseCsv(CSVFormat.RFC4180.builder().setDelimiter(';').get())
  .runForeach(println)
```

## flow.renderCsv

Writes each row as one string, followed by the format's record separator; a field is quoted only if needed. Encode the
result to get bytes. Each row becomes one chunk, so with many small rows write through a `BufferedOutputStream` using
`runToOutputStream`.

```scala mdoc:compile-only
import ox.flow.Flow
import ox.flow.csv.*
import java.nio.file.Paths

Flow
  .fromValues(List("id", "message"), List("1", "created, ok"))
  .renderCsv()
  .encodeUtf8
  .runToFile(Paths.get("events.csv"))
```
