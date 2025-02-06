package ox.flow.internal

case class WeightedHeap[T](
    private val heap: Vector[(T, Long)] = Vector.empty[(T, Long)],
    private val valueToIndex: Map[T, Int] = Map.empty[T, Int]
):

  private def parentIndex(i: Int): Int = (i - 1) / 2
  private def leftChildIndex(i: Int): Int = 2 * i + 1
  private def rightChildIndex(i: Int): Int = 2 * i + 2

  private def swap(heap: Vector[(T, Long)], valueToIndex: Map[T, Int], i: Int, j: Int): (Vector[(T, Long)], Map[T, Int]) =
    val updatedHeap = heap.updated(i, heap(j)).updated(j, heap(i))
    val updatedMap = valueToIndex
      .updated(heap(i)._1, j)
      .updated(heap(j)._1, i)
    (updatedHeap, updatedMap)

  private def bubbleUp(heap: Vector[(T, Long)], valueToIndex: Map[T, Int], i: Int): (Vector[(T, Long)], Map[T, Int]) =
    var currentIndex = i
    var currentHeap = heap
    var currentMap = valueToIndex

    while currentIndex > 0 && currentHeap(currentIndex)._2 < currentHeap(parentIndex(currentIndex))._2 do
      val (newHeap, newMap) = swap(currentHeap, currentMap, currentIndex, parentIndex(currentIndex))
      currentHeap = newHeap
      currentMap = newMap
      currentIndex = parentIndex(currentIndex)
    (currentHeap, currentMap)
  end bubbleUp

  private def bubbleDown(heap: Vector[(T, Long)], valueToIndex: Map[T, Int], i: Int): (Vector[(T, Long)], Map[T, Int]) =
    var currentIndex = i
    var currentHeap = heap
    var currentMap = valueToIndex

    while true do
      val left = leftChildIndex(currentIndex)
      val right = rightChildIndex(currentIndex)
      var smallest = currentIndex

      if left < currentHeap.length && currentHeap(left)._2 < currentHeap(smallest)._2 then smallest = left
      if right < currentHeap.length && currentHeap(right)._2 < currentHeap(smallest)._2 then smallest = right

      if smallest == currentIndex then return (currentHeap, currentMap)

      val (newHeap, newMap) = swap(currentHeap, currentMap, currentIndex, smallest)
      currentHeap = newHeap
      currentMap = newMap
      currentIndex = smallest
    end while
    throw new IllegalStateException("Bubble down should terminate before this point")
  end bubbleDown

  def insert(value: T, weight: Long): WeightedHeap[T] =
    valueToIndex.get(value) match
      case Some(_) => updateWeight(value, weight)
      case None =>
        val newHeap = heap :+ (value, weight)
        val newMap = valueToIndex + (value -> (newHeap.length - 1))
        val (finalHeap, finalMap) = bubbleUp(newHeap, newMap, newHeap.length - 1)
        WeightedHeap(finalHeap, finalMap)

  def extractMin(): (Option[(T, Long)], WeightedHeap[T]) =
    if heap.isEmpty then (None, this)
    else if heap.length == 1 then (Some(heap.head), WeightedHeap(Vector.empty, Map.empty))
    else
      val min = heap.head
      val last = heap.last
      val updatedHeap = heap.updated(0, last).init
      val updatedMap = valueToIndex.updated(last._1, 0) - min._1
      val (finalHeap, finalMap) = bubbleDown(updatedHeap, updatedMap, 0)
      (Some(min), WeightedHeap(finalHeap, finalMap))

  def updateWeight(value: T, newWeight: Long): WeightedHeap[T] =
    valueToIndex.get(value) match
      case Some(index) =>
        val currentWeight = heap(index)._2
        if newWeight == currentWeight then this
        else
          val updatedHeap = heap.updated(index, (value, newWeight))
          if newWeight < currentWeight then
            val (finalHeap, finalMap) = bubbleUp(updatedHeap, valueToIndex, index)
            WeightedHeap(finalHeap, finalMap)
          else
            val (finalHeap, finalMap) = bubbleDown(updatedHeap, valueToIndex, index)
            WeightedHeap(finalHeap, finalMap)
        end if
      case None => throw new NoSuchElementException(s"Value $value not found in heap")

  def peekMin(): Option[(T, Long)] = heap.headOption

  def size: Int = heap.size

  def isEmpty: Boolean = heap.isEmpty

  def contains(t: T): Boolean = valueToIndex.contains(t)
end WeightedHeap
