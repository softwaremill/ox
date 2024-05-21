# Transforming sources 

## Transforming eagerly

Sources can be transformed by receiving values, manipulating them and sending to other channels - this provides the
highest flexibility and allows creating arbitrary channel topologies.

However, there's a number of common operations that are built-in as methods on `Source`, which allow transforming the
source. For example:

```scala mdoc:compile-only
import ox.supervised
import ox.channels.{Channel, Source}

supervised {
  val c = Channel.rendezvous[String]
  val c2: Source[Int] = c.map(s => s.length())
}
```

The `.map` needs to be run within a scope, as it starts a new virtual thread (using `fork`), which:

* immediately starts receiving values from the given source
* applies the given function
* sends the result to the new channel

The new channel is returned to the user as the return value of `.map`.

Some other available combinators include `.filter`, `.take`, `.zip(otherSource)`, `.merge(otherSource)` etc.

To run multiple transformations within one virtual thread / fork, the `.transform` method is available:

```scala mdoc:compile-only
import ox.supervised
import ox.channels.Source

supervised {
  Source.iterate(0)(_ + 1) // natural numbers
    .transform(_.filter(_ % 2 == 0).map(_ + 1).take(10)) // take the 10 first even numbers, incremented by 1
    .foreach(n => println(n.toString))
}
```

## Capacity of transformation stages

Most source transformation methods create new channels, on which the transformed values are produced. The capacity of
these channels by default is 16 (buffered). This can be overridden by providing `StageCapacity` given, e.g.:

```scala
(v: Source[Int]).map(_ + 1)(using StageCapacity(10))
```

## Transforming lazily

A limited number of transformations can be applied to a source without creating a new channel and a new fork, which
computes the transformation. These include: `.mapAsView`, `.filterAsView` and `.collectAsView`.

For example:

```scala mdoc:compile-only
import ox.channels.{Channel, Source}

val c = Channel.rendezvous[String]
val c2: Source[Int] = c.mapAsView(s => s.length())
```

The mapping function (`s => s.length()`) will only be invoked when the source is consumed (using `.receive()` or 
`select`), on the consumer's thread. This is in contrast to `.map`, where the mapping function is invoked on a separate
fork.

Hence, creating views doesn't need to be run within a scope, and creating the view itself doesn't consume any elements
from the source on which it is run.

## Text transformations

When dealing with Sources with chunks of bytes or Strings, you can leverage following built-in combinators for useful transformations:

* `encodeUtf8` encodes a `Source[String]` into a `Source[Chunk[Byte]]`
* `linesUtf8` decodes a `Source[Chunk[Byte]]` into a `Source[String]`. Assumes that the input represents text with line breaks. The `String` elements emitted by resulting `Source[String]` represent text lines.
* `decodeStringUtf8` to decode a `Source[Chunk[Byte]]` into a `Source[String]`, without handling line breaks, just processing input bytes as UTF-8 characters, even if a multi-byte character is divided into two chunks.

Such operations may be useful when dealing with I/O like files, `InputStream`, etc.. See [examples here](io.md).
