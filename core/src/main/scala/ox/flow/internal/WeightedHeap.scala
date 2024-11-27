package ox.flow.internal

import scala.collection.mutable
import ox.discard

class WeightedHeap[T]:
  private val heap = mutable.ArrayBuffer[(T, Long)]()
  private val valueToIndex = mutable.HashMap[T, Int]()

  private def parentIndex(i: Int): Int = (i - 1) / 2
  private def leftChildIndex(i: Int): Int = 2 * i + 1
  private def rightChildIndex(i: Int): Int = 2 * i + 2

  private def swap(i: Int, j: Int): Unit =
    val temp = heap(i)
    heap(i) = heap(j)
    heap(j) = temp
    valueToIndex(heap(i)._1) = i
    valueToIndex(heap(j)._1) = j

  private def bubbleUp(i: Int): Unit =
    var currentIndex = i
    while currentIndex > 0 && heap(currentIndex)._2 < heap(parentIndex(currentIndex))._2 do
      swap(currentIndex, parentIndex(currentIndex))
      currentIndex = parentIndex(currentIndex)

  private def bubbleDown(i: Int): Unit =
    var currentIndex = i
    while true do
      val left = leftChildIndex(currentIndex)
      val right = rightChildIndex(currentIndex)
      var smallest = currentIndex

      if left < heap.length && heap(left)._2 < heap(smallest)._2 then smallest = left
      if right < heap.length && heap(right)._2 < heap(smallest)._2 then smallest = right

      if smallest == currentIndex then return

      swap(currentIndex, smallest)
      currentIndex = smallest
    end while
  end bubbleDown

  def insert(value: T, weight: Long): Unit =
    if valueToIndex.contains(value) then updateWeight(value, weight)
    else
      heap.append((value, weight))
      valueToIndex(value) = heap.length - 1
      bubbleUp(heap.length - 1)

  def extractMin(): Option[(T, Long)] =
    if heap.isEmpty then return None
    val min = heap.head
    val last = heap.remove(heap.length - 1)
    valueToIndex.remove(min._1).discard

    if heap.nonEmpty then
      heap(0) = last
      valueToIndex(last._1) = 0
      bubbleDown(0)

    Some(min)
  end extractMin

  def updateWeight(value: T, newWeight: Long): Unit =
    valueToIndex.get(value) match
      case Some(index) =>
        val oldWeight = heap(index)._2
        heap(index) = (value, newWeight)
        if newWeight < oldWeight then bubbleUp(index)
        else bubbleDown(index)
      case None =>
        throw new NoSuchElementException(s"Value $value not found in heap")

  def peekMin(): Option[(T, Long)] = heap.headOption

  def isEmpty: Boolean = heap.isEmpty
  def size: Int = heap.size

  def contains(value: T): Boolean = valueToIndex.contains(value)
end WeightedHeap
