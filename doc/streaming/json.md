# Flows: JSON

Dependency:

```scala
"com.softwaremill.ox" %% "flow-json" % "@VERSION@"
```

Ox can parse a `Flow[Chunk[Byte]]` into values, and render values back into byte chunks, either as newline-delimited JSON
(NDJSON) or as a single top-level JSON array. Values are converted using
[jsoniter-scala](https://github.com/plokhotnyuk/jsoniter-scala), so each operation needs a `JsonValueCodec[T]` in scope.
Codecs are derived at compile time, which needs one more dependency:

```scala
"com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-macros" % "@JSONITER_VERSION@" % "compile-internal"
```

```scala mdoc:silent
import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker

case class Event(id: Long, message: String)

given JsonValueCodec[Event] = JsonCodecMaker.make
```

A derived codec rejects a JSON `null`. To read one, derive the codec for `Option[T]`, which maps `null` to `None`.

## NDJSON

### flow.parseNdjson

Splits the input into LF-delimited records and parses each one. Lines holding only spaces, tabs and CRs are skipped, and
CRLF endings, a final record without an LF and one leading byte-order mark are accepted. Every other line must hold
exactly one JSON value; content after the value fails the flow.

A record is buffered until its LF, or until the end of the input for the final one. Records may be at most
`maxRecordBytes` long, 32 MiB by default; a longer one fails the flow.

```scala mdoc:compile-only
import ox.flow.Flow
import ox.flow.json.*
import java.nio.file.Paths

Flow
  .fromFile(Paths.get("events.ndjson"))
  .parseNdjson[Event]()
  .filter(_.id > 1)
  .runForeach(println)
```

### flow.renderNdjson

Writes each value followed by an LF, including the last one. The `WriterConfig` must not indent, so that each value stays
on one line; line breaks inside strings are escaped anyway.

```scala mdoc:compile-only
import ox.flow.Flow
import ox.flow.json.*
import java.nio.file.Paths

Flow
  .fromValues(Event(1, "created"), Event(2, "updated"))
  .renderNdjson()
  .runToFile(Paths.get("events.ndjson"))
```

## JSON arrays

### flow.parseJsonArray

Reads exactly one top-level array, emitting its elements as they are parsed. A byte-order mark is not stripped. An
incomplete array and a non-array top-level value fail the flow; a top-level `null` is read as an empty array. Content
after the array fails the flow as well, so the flow completes only once the source ends.

Reading the input creates an asynchronous boundary, so chunks are consumed ahead of downstream demand.

```scala mdoc:compile-only
import ox.flow.Flow
import ox.flow.json.*
import java.nio.file.Paths

Flow
  .fromFile(Paths.get("events.json"))
  .parseJsonArray[Event]()
  .runForeach(println)
```

### flow.renderJsonArray

Writes `[` and `]` around the comma-separated values; an empty flow renders as `[]`. If the flow fails after output has
started, the output is left as an incomplete array.

```scala mdoc:compile-only
import ox.flow.Flow
import ox.flow.json.*
import java.nio.file.Paths

Flow
  .fromValues(Event(1, "created"), Event(2, "updated"))
  .renderJsonArray()
  .runToFile(Paths.get("events.json"))
```
