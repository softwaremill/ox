package ox

import scala.reflect.{ClassTag, classTag}

/** An immutable finite indexed sequence of elements, backed by Array. Currently represents only a think wrapper, delegating all operations
  * from `IndexedSeq` directly to underlying `Array` equivalents. Such operations can be expensive when you want to do concatenation,
  * splitAt, drop, etc. - transformations that are often useful when doing Source processing. `Chunk` should therefore evolve towards a
  * performance-optimized, underneath leveraging Arrays wrapped with lazy data structures to avoid unnecessary data copying.
  */
abstract sealed class Chunk[+A] extends IndexedSeq[A]:
  override def drop(n: Int): Chunk[A] = this match
    case a: ArrayChunk[?] =>
      ArrayChunk(a.array.drop(n))
    case Empty =>
      Empty

  override def take(n: Int): Chunk[A] = this match
    case a: ArrayChunk[?] =>
      ArrayChunk(a.array.take(n))
    case Empty =>
      Empty

  override def splitAt(n: Int): (Chunk[A], Chunk[A]) = (take(n), drop(n))
  final def ++[A1 >: A](that: Chunk[A1]): Chunk[A1] =
    given ct: ClassTag[A1] = that match {
      case a: ArrayChunk[_] => ClassTag(a.array.getClass.getComponentType)
      case Empty            => classTag[java.lang.Object].asInstanceOf[ClassTag[A1]]
    }
    Chunk.fromArray(toArray[A1] ++ that.toArray)

  /** Converts a chunk of into a String, if supported by element type (for example for byte chunks). */
  final def asString(using ev: Chunk.IsText[A]): String =
    ev.convert(this)

final case class ArrayChunk[A](array: Array[A]) extends Chunk[A]:
  override def apply(i: Int): A = array(i)
  override def iterator: Iterator[A] = array.iterator
  override def length: Int = array.length
  override def take(n: Int): Chunk[A] = ArrayChunk(array.take(n))
  override def toArray[A1 >: A: ClassTag]: Array[A1] = array.asInstanceOf[Array[A1]]
  override def indexWhere(f: A => Boolean, from: Int): Int = array.indexWhere(f, from)

case object Empty extends Chunk[Nothing]:
  override def apply(i: Int): Nothing = throw new IndexOutOfBoundsException(s"Empty($i) called")
  override def iterator: Iterator[Nothing] = Iterator.empty
  override def length: Int = 0
  override def toArray[A1: ClassTag]: Array[A1] =
    Array.empty
  override def indexWhere(f: Nothing => Boolean, from: Int): Int = -1

object Chunk:
  def empty[A]: Chunk[A] = Empty

  def fromArray[A](array: Array[A]): Chunk[A] =
    ArrayChunk(array)

  sealed trait IsText[-T] {
    def convert(chunk: Chunk[T]): String
  }

  object IsText {
    given byteIsText: IsText[Byte] =
      new IsText[Byte] { def convert(chunk: Chunk[Byte]): String = new String(chunk.toArray) }
  }
