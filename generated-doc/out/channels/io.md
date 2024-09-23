# Files and I/O

Ox allows creating a `Source` which reads from a file or `InpuStream`, as well as directing an existing source into a file or an `OutputStream`. These methods work only with a `Source[Chunk[Byte]]`. Ox takes care of closing files/streams after processing and on errors.

## InputStream and OutputStream

### Source.fromInputStream 

An `InputStream` can be converted to a `Source[Chunk[Byte]]`:

```scala
import ox.channels.Source
import ox.supervised
import java.io.ByteArrayInputStream
import java.io.InputStream

val inputStream: InputStream = new ByteArrayInputStream("some input".getBytes) 
supervised {
  Source
    .fromInputStream(inputStream) // Source[Chunk[Byte]]
    .decodeStringUtf8
    .map(_.toUpperCase)
    .foreach(println) // "SOME INPUT"
}
```

You can define a custom chunk size instead of using the default:


```scala
import ox.channels.Source
import ox.supervised
import java.io.ByteArrayInputStream
import java.io.InputStream

val inputStream: InputStream = new ByteArrayInputStream("some input".getBytes) 
supervised {
  Source
    .fromInputStream(inputStream, chunkSize = 4) // Source[Chunk[Byte]]
    .decodeStringUtf8
    .map(_.toUpperCase)
    .foreach(println) // "SOME", " INPUT"
}
```

### source.toOutputStream

A `Source[Chunk[Byte]]` can be directed to write to an `OutputStream`:

```scala
import ox.channels.Source
import ox.supervised
import java.io.ByteArrayOutputStream

val outputStream = new ByteArrayOutputStream()
supervised {
  val source = Source.fromIterable(List("text1,", "text2"))
  source
    .encodeUtf8
    .toOutputStream(outputStream)
}
outputStream.toString // "TEXT1,TEXT2"
```

## Files

### Source.fromFile

You can obtain a `Source` of byte chunks read from a file for a given path:

```scala
import ox.channels.Source
import ox.supervised
import java.nio.file.Paths

supervised {
  Source
    .fromFile(Paths.get("/path/to/my/file.txt"))
    .linesUtf8
    .map(_.toUpperCase)
    .toList // List("FILE_LINE1", "FILE_LINE2")
}
```

Similarly to `.fromInputStream`, you can define custom chunk size using `Source.fromFile(path: Path, chunkSize: Int)`.

### source.toFile

A `Source[Chunk[Byte]]` can be written to a file under a given path:

```scala
import ox.channels.Source
import ox.supervised
import java.nio.file.Paths

supervised {
  val source = Source.fromIterable(List("text1,", "text2"))
  source
    .encodeUtf8
    .toFile(Paths.get("/path/to/my/target/file.txt"))
}
```
