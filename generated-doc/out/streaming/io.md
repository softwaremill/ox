# Files and I/O

Ox allows creating a `Flow` which reads from a file or `InputStream`, as well as running a flow into a file or an `OutputStream`. The latter methods are available for `Flow[Chunk[Byte]]`. Ox takes care of closing files/streams after processing and on errors.

## InputStream and OutputStream

### Flow.fromInputStream 

A `Flow[Chunk[Byte]]` can be created using a `InputStream`:

```scala
import ox.flow.Flow
import java.io.ByteArrayInputStream
import java.io.InputStream

val inputStream: InputStream = new ByteArrayInputStream("some input".getBytes) 

Flow
  .fromInputStream(inputStream) // Flow[Chunk[Byte]]
  .decodeStringUtf8
  .map(_.toUpperCase)
  .runForeach(println) // "SOME INPUT"
```

You can define a custom chunk size instead of using the default:

```scala
import ox.flow.Flow
import java.io.ByteArrayInputStream
import java.io.InputStream

val inputStream: InputStream = new ByteArrayInputStream("some input".getBytes) 
Flow
  .fromInputStream(inputStream, chunkSize = 4) // Flow[Chunk[Byte]]
  .decodeStringUtf8
  .map(_.toUpperCase)
  .runForeach(println) // "SOME", " INPUT"
```

### flow.toOutputStream

A `Flow[Chunk[Byte]]` can be run to write to an `OutputStream`:

```scala
import ox.flow.Flow
import java.io.ByteArrayOutputStream

val outputStream = new ByteArrayOutputStream()

val flow = Flow.fromIterable(List("text1,", "text2"))
flow
  .encodeUtf8
  .runToOutputStream(outputStream)

outputStream.toString // "TEXT1,TEXT2"
```

## Files

### Flow.fromFile

You can obtain a `Flow` of byte chunks read from a file for a given path:

```scala
import ox.flow.Flow
import java.nio.file.Paths

Flow
  .fromFile(Paths.get("/path/to/my/file.txt"))
  .linesUtf8
  .map(_.toUpperCase)
  .runToList() // List("FILE_LINE1", "FILE_LINE2")
```

Similarly to `.fromInputStream`, you can define custom chunk size using `Flow.fromFile(path: Path, chunkSize: Int)`.

### flow.toFile

A `Flow[Chunk[Byte]]` can be written to a file under a given path:

```scala
import ox.flow.Flow
import java.nio.file.Paths

Flow.fromIterable(List("text1,", "text2"))
  .encodeUtf8
  .runToFile(Paths.get("/path/to/my/target/file.txt"))
```
