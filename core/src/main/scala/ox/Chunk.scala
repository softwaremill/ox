package ox

import scala.reflect.{ClassTag, classTag}

abstract sealed class Chunk[+A]:
  def drop(n: Int): Chunk[A]
  def indexWhere(f: A => Boolean): Int
  def iterator: Iterator[A]
  def length: Int
  def splitAt(n: Int): (Chunk[A], Chunk[A])
  def toArray[A1 >: A: ClassTag]: Array[A1]

  final def ++[A1 >: A](that: Chunk[A1]): Chunk[A1] =
    given ct: ClassTag[A1] = that match {
      case a: ArrayChunk[_] => ClassTag(a.array.getClass.getComponentType)
      case Empty            => classTag[java.lang.Object].asInstanceOf[ClassTag[A1]]
    }
    Chunk.fromArray(toArray[A1] ++ that.toArray)

  final def asString(using ev: Chunk.IsText[A]) =
    ev.convert(this)

final case class ArrayChunk[A](array: Array[A]) extends Chunk[A]:
  override def drop(n: Int): Chunk[A] = ArrayChunk(array.drop(n))
  override def iterator: Iterator[A] = array.iterator
  override def length: Int = array.length
  override def toArray[A1 >: A: ClassTag]: Array[A1] = array.asInstanceOf[Array[A1]]
  override def indexWhere(f: A => Boolean): Int = array.indexWhere(f)
  override def splitAt(n: Int): (Chunk[A], Chunk[A]) =
    array.splitAt(n) match
      case (a, b) => (Chunk.fromArray(a), Chunk.fromArray(b))

case object Empty extends Chunk[Nothing]:
  override def drop(n: Int): Chunk[Nothing] = this
  override def iterator: Iterator[Nothing] = Iterator.empty
  override def length: Int = 0
  override def toArray[A1: ClassTag]: Array[A1] =
    Array.empty
  override def indexWhere(f: Nothing => Boolean): Int = -1
  override def splitAt(n: Int): (Chunk[Nothing], Chunk[Nothing]) = (this, this)

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
