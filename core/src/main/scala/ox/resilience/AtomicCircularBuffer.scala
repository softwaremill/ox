package ox.resilience

import scala.reflect.ClassTag
import java.util.concurrent.Semaphore

class AtomicCircularBuffer[T: ClassTag](size: Int):
  private val semaphore = Semaphore(1)
  private var writeIndex = 0
  private var readIndex = 0
  private val buffer = Array.fill[Option[T]](size)(None)
  def push(item: T): Unit =
    semaphore.acquire()
    try
      buffer(writeIndex) = Some(item)
      writeIndex = (writeIndex + 1) % size
    finally semaphore.release()
  def pop: Option[T] =
    semaphore.acquire()
    try
      val result = buffer(readIndex)
      readIndex = (readIndex + 1) % size
      result
    finally semaphore.release()
  def peak: Option[T] = buffer(readIndex)
  def snapshot: Array[T] =
    semaphore.acquire()
    try buffer.clone().flatMap(identity)
    finally semaphore.release()
end AtomicCircularBuffer
