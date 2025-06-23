package ox

import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import scala.reflect.ClassTag

/** An immutable finite indexed sequence of elements, backed by multiple IArrays. Optimized for efficient concatenation by maintaining a
  * sequence of backing arrays. Concatenation becomes O(1) by combining array sequences, while iteration efficiently traverses across
  * multiple arrays.
  *
  * Optimized implementations for some collection operations are provided, basing on usage in [[ox.flow.FlowTextOps]] and
  * [[ox.flow.FlowIOOps]].
  */
abstract sealed class Chunk[+A] extends IndexedSeq[A]:
  /** Returns the backing arrays of this chunk for optimized processing */
  def backingArrays: List[IArray[A]]

  override def drop(n: Int): Chunk[A] =
    if n <= 0 then this
    else if n >= length then Chunk.empty
    else
      val arrays = backingArrays
      dropFromArrays(arrays, n)

  override def take(n: Int): Chunk[A] =
    if n <= 0 then Chunk.empty
    else if n >= length then this
    else
      val arrays = backingArrays
      takeFromArrays(arrays, n)

  override def indexWhere(f: A => Boolean, from: Int): Int =
    if from < 0 then indexWhere(f, 0)
    else if from >= length then -1
    else
      val arrays = backingArrays
      var currentIndex = 0
      var arrayIndex = 0

      // Skip arrays until we reach the starting position
      while arrayIndex < arrays.length && currentIndex + arrays(arrayIndex).length <= from do
        currentIndex += arrays(arrayIndex).length
        arrayIndex += 1
      end while

      if arrayIndex >= arrays.length then -1
      else
        // Start searching from the appropriate position in the current array
        val startPosInArray = from - currentIndex
        var pos = arrays(arrayIndex).indexWhere(f, startPosInArray)

        if pos >= 0 then currentIndex + pos
        else
          // Continue searching in subsequent arrays
          currentIndex += arrays(arrayIndex).length
          arrayIndex += 1

          while arrayIndex < arrays.length do
            pos = arrays(arrayIndex).indexWhere(f)
            if pos >= 0 then return currentIndex + pos
            currentIndex += arrays(arrayIndex).length
            arrayIndex += 1
          end while

          -1
        end if
      end if

  def concat[B >: A](that: Chunk[B]): Chunk[B] =
    if this.isEmpty then that
    else if that.isEmpty then this
    else NonEmptyChunk(this.backingArrays ++ that.backingArrays)
  end concat

  def ++[B >: A](that: Chunk[B]): Chunk[B] = concat(that)

  override def splitAt(n: Int): (Chunk[A], Chunk[A]) = (take(n), drop(n))

  /** Converts a chunk of into a String, if supported by element type (for example for byte chunks). */
  final def asStringUtf8(using ev: Chunk.IsText[A]): String =
    ev.convert(this, StandardCharsets.UTF_8)

  final def asString(charset: Charset)(using ev: Chunk.IsText[A]): String =
    ev.convert(this, charset)

  private def dropFromArrays[A1 >: A](arrays: List[IArray[A1]], n: Int): Chunk[A1] =
    var remaining = n
    var currentArrays = arrays

    while currentArrays.nonEmpty && remaining > 0 do
      val currentArray = currentArrays.head
      if remaining >= currentArray.length then
        remaining -= currentArray.length
        currentArrays = currentArrays.tail
      else
        // Need to slice the current array
        val slicedArray = currentArray.slice(remaining, currentArray.length)
        return NonEmptyChunk(slicedArray :: currentArrays.tail)
      end if
    end while

    if currentArrays.isEmpty then Chunk.empty
    else NonEmptyChunk(currentArrays)
  end dropFromArrays

  private def takeFromArrays[A1 >: A](arrays: List[IArray[A1]], n: Int): Chunk[A1] =
    var remaining = n
    var result = List.empty[IArray[A1]]
    var currentArrays = arrays

    while currentArrays.nonEmpty && remaining > 0 do
      val currentArray = currentArrays.head
      if remaining >= currentArray.length then
        result = currentArray :: result
        remaining -= currentArray.length
        currentArrays = currentArrays.tail
      else
        // Need to slice the current array
        val slicedArray = currentArray.slice(0, remaining)
        result = slicedArray :: result
        remaining = 0
      end if
    end while

    NonEmptyChunk(result.reverse)
  end takeFromArrays
end Chunk

/** Chunk backed by one or more IArrays */
final case class NonEmptyChunk[A](arrays: List[IArray[A]]) extends Chunk[A]:
  override def backingArrays: List[IArray[A]] = arrays

  override def apply(i: Int): A =
    if i < 0 || i >= length then throw new IndexOutOfBoundsException(s"NonEmptyChunk($i) with length $length")

    var remaining = i
    var currentArrays = arrays
    while currentArrays.nonEmpty do
      val currentArray = currentArrays.head
      if remaining < currentArray.length then return currentArray(remaining)
      remaining -= currentArray.length
      currentArrays = currentArrays.tail

    throw new IndexOutOfBoundsException(s"Index $i out of bounds")
  end apply

  override def iterator: Iterator[A] = new MultiArrayIterator(arrays)

  private lazy val _length: Int = arrays.map(_.length).sum
  override def length: Int = _length

  override def toArray[A1 >: A: ClassTag]: Array[A1] =
    val result = new Array[A1](length)
    var pos = 0
    for array <- arrays do
      Array.copy(IArray.genericWrapArray(array).toArray, 0, result, pos, array.length)
      pos += array.length
    result
end NonEmptyChunk

/** Iterator that efficiently traverses multiple IArrays */
private class MultiArrayIterator[A](arrays: List[IArray[A]]) extends Iterator[A]:
  private var currentArrays = arrays
  private var currentArrayIterator: Iterator[A] = if arrays.nonEmpty then arrays.head.iterator else Iterator.empty

  def hasNext: Boolean =
    while currentArrayIterator.isEmpty && currentArrays.tail.nonEmpty do
      currentArrays = currentArrays.tail
      currentArrayIterator = currentArrays.head.iterator
    currentArrayIterator.hasNext

  def next(): A =
    if !hasNext then throw new NoSuchElementException
    currentArrayIterator.next()
end MultiArrayIterator

case object Empty extends Chunk[Nothing]:
  override def backingArrays: List[IArray[Nothing]] = Nil
  override def apply(i: Int): Nothing = throw new IndexOutOfBoundsException(s"Empty($i) called")
  override def iterator: Iterator[Nothing] = Iterator.empty
  override def length: Int = 0
  override def toArray[A1: ClassTag]: Array[A1] = Array.empty

object Chunk:
  def empty[A]: Chunk[A] = Empty

  def fromArray[A: ClassTag](array: Array[A]): Chunk[A] =
    if array.isEmpty then Empty else NonEmptyChunk(List(IArray.unsafeFromArray(array)))

  def fromIArray[A: ClassTag](array: IArray[A]): Chunk[A] =
    if array.isEmpty then Empty else NonEmptyChunk(List(array))

  /** Creates a chunk from elements */
  def apply[A: ClassTag](elements: A*): Chunk[A] =
    if elements.isEmpty then Empty
    else NonEmptyChunk(List(IArray.from(elements.toArray)))

  sealed trait IsText[-T]:
    def convert(chunk: Chunk[T], charset: Charset): String

  object IsText:
    given byteIsText: IsText[Byte] =
      new IsText[Byte]:
        def convert(chunk: Chunk[Byte], charset: Charset): String = new String(chunk.toArray, charset)
end Chunk
