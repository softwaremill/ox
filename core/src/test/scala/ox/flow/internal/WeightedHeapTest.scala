package ox.flow.internal

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.discard
import ox.tap

class WeightedHeapTest extends AnyFlatSpec with Matchers:
  behavior of "WeightedHeap"

  // insert & extract

  it should "allow inserting elements with weights" in:
    var heap = new WeightedHeap[String]()
    heap = heap.insert("A", 5L)
    heap = heap.insert("B", 3L)
    heap = heap.insert("C", 8L)

    heap.size shouldEqual 3
    heap.peekMin() shouldEqual Some(("B", 3L))

  it should "allow extracting the minimum element" in:
    var heap = new WeightedHeap[String]()
    heap = heap.insert("A", 5L)
    heap = heap.insert("B", 3L)
    heap = heap.insert("C", 8L)

    heap.extractMin().tap(mh => heap = mh._2)._1 shouldEqual Some(("B", 3L))
    heap.size shouldEqual 2
    heap.peekMin() shouldEqual Some(("A", 5L))

  it should "return None when extracting from an empty heap" in:
    val heap = new WeightedHeap[String]()
    heap.extractMin()._1 shouldEqual None

  // size

  it should "return the correct size after operations" in:
    var heap = new WeightedHeap[String]()
    heap.size shouldEqual 0

    heap = heap.insert("A", 5L)
    heap = heap.insert("B", 3L)
    heap = heap.insert("C", 8L)
    heap.size shouldEqual 3

    heap.extractMin().tap(mh => heap = mh._2).discard
    heap.size shouldEqual 2

    heap.extractMin().tap(mh => heap = mh._2).discard
    heap.extractMin().tap(mh => heap = mh._2).discard
    heap.size shouldEqual 0

  it should "handle empty heaps correctly" in:
    var heap = new WeightedHeap[String]()
    heap.isEmpty shouldEqual true
    heap.size shouldEqual 0

    heap = heap.insert("A", 5L)
    heap.isEmpty shouldEqual false

    heap = heap.extractMin()._2
    heap.isEmpty shouldEqual true

  // decreasing weights

  it should "update the weight of an existing element and adjust its position" in:
    var heap = new WeightedHeap[String]()
    heap = heap.insert("A", 5L)
    heap = heap.insert("B", 3L)
    heap = heap.insert("C", 8L)

    heap = heap.updateWeight("A", 2L)
    heap.peekMin() shouldEqual Some(("A", 2L))

    heap = heap.updateWeight("C", 1L)
    heap.peekMin() shouldEqual Some(("C", 1L))

  it should "throw an exception when updating the weight of a non-existent element" in:
    var heap = new WeightedHeap[String]()
    heap = heap.insert("A", 5L)

    an[NoSuchElementException] should be thrownBy heap.updateWeight("B", 3L)

  it should "handle multiple insertions and updates correctly" in:
    var heap = new WeightedHeap[String]()
    heap = heap.insert("A", 10L)
    heap = heap.insert("B", 15L)
    heap = heap.insert("C", 5L)
    heap = heap.insert("D", 20L)

    heap.peekMin() shouldEqual Some(("C", 5L))

    heap = heap.updateWeight("A", 2L)
    heap.peekMin() shouldEqual Some(("A", 2L))

    heap = heap.updateWeight("D", 1L)
    heap.peekMin() shouldEqual Some(("D", 1L))

    heap.size shouldEqual 4

  it should "handle duplicate insertions by updating the existing element's weight" in:
    var heap = new WeightedHeap[String]()
    heap = heap.insert("A", 5L)
    heap = heap.insert("A", 2L)

    heap.size shouldEqual 1
    heap.peekMin() shouldEqual Some(("A", 2L))

  // increasing weights

  it should "handle increasing the weight of an existing element" in:
    var heap = new WeightedHeap[String]()
    heap = heap.insert("A", 3L)
    heap = heap.insert("B", 2L)
    heap = heap.insert("C", 1L)

    // Initially, "C" should be the smallest element
    heap.peekMin() shouldEqual Some(("C", 1L))

    // Increase the weight of "C" to 5
    heap = heap.updateWeight("C", 5L)

    // Now "B" should be the smallest element
    heap.peekMin() shouldEqual Some(("B", 2L))

    // Extract min to ensure heap adjusts correctly
    heap.extractMin().tap(mh => heap = mh._2)._1 shouldEqual Some(("B", 2L))
    heap.peekMin() shouldEqual Some(("A", 3L))

    // Extract min again and ensure "C" is now the largest
    heap.extractMin().tap(mh => heap = mh._2)._1 shouldEqual Some(("A", 3L))
    heap.peekMin() shouldEqual Some(("C", 5L))

  it should "maintain heap property after multiple weight increases" in:
    var heap = new WeightedHeap[String]()
    heap = heap.insert("X", 1L)
    heap = heap.insert("Y", 2L)
    heap = heap.insert("Z", 3L)

    // Increase weights of multiple elements
    heap = heap.updateWeight("X", 6L)
    heap = heap.updateWeight("Y", 5L)

    // After the updates, "Z" should be the smallest
    heap.peekMin() shouldEqual Some(("Z", 3L))

    // Extract min and verify the order
    heap.extractMin().tap(mh => heap = mh._2)._1 shouldEqual Some(("Z", 3L))
    heap.extractMin().tap(mh => heap = mh._2)._1 shouldEqual Some(("Y", 5L))
    heap.extractMin().tap(mh => heap = mh._2)._1 shouldEqual Some(("X", 6L))

  it should "work correctly when increasing the weight of the current minimum element" in:
    var heap = new WeightedHeap[String]()
    heap = heap.insert("P", 1L)
    heap = heap.insert("Q", 2L)
    heap = heap.insert("R", 3L)

    // Initially, "P" is the minimum element
    heap.peekMin() shouldEqual Some(("P", 1L))

    // Increase the weight of "P" to 4
    heap = heap.updateWeight("P", 4L)

    // Now "Q" should be the minimum
    heap.peekMin() shouldEqual Some(("Q", 2L))

    // Extract min to verify order
    heap.extractMin().tap(mh => heap = mh._2)._1 shouldEqual Some(("Q", 2L))
    heap.extractMin().tap(mh => heap = mh._2)._1 shouldEqual Some(("R", 3L))
    heap.extractMin().tap(mh => heap = mh._2)._1 shouldEqual Some(("P", 4L))

  it should "handle increasing weights in a large heap" in:
    var heap = new WeightedHeap[Int]()
    val elements = (1 to 10).map(i => (i, i.toLong)) // Insert elements with weights equal to their values
    elements.foreach { case (value, weight) => heap = heap.insert(value, weight) }

    // Increase weights of some elements
    heap = heap.updateWeight(1, 15L) // Increase weight of smallest element
    heap = heap.updateWeight(5, 12L) // Increase weight of a middle element

    // After updates, the new minimum should be "2"
    heap.peekMin() shouldEqual Some((2, 2L))

    // Extract elements in order to verify heap property
    val result = (1 to 10).flatMap(_ => heap.extractMin().tap(mh => heap = mh._2)._1)
    result.map(_._1) shouldEqual Seq(2, 3, 4, 6, 7, 8, 9, 10, 5, 1) // Order adjusted by weight

  // multiple operations

  it should "maintain the heap property after multiple operations" in:
    var heap = new WeightedHeap[Int]()
    heap = heap.insert(10, 10L)
    heap = heap.insert(20, 20L)
    heap = heap.insert(30, 30L)
    heap = heap.insert(5, 5L)
    heap = heap.insert(15, 15L)

    heap.extractMin().tap(mh => heap = mh._2)._1 shouldEqual Some((5, 5L))
    heap.extractMin().tap(mh => heap = mh._2)._1 shouldEqual Some((10, 10L))
    heap.extractMin().tap(mh => heap = mh._2)._1 shouldEqual Some((15, 15L))
    heap.extractMin().tap(mh => heap = mh._2)._1 shouldEqual Some((20, 20L))
    heap.extractMin().tap(mh => heap = mh._2)._1 shouldEqual Some((30, 30L))
    heap.extractMin().tap(mh => heap = mh._2)._1 shouldEqual None

  it should "work with large numbers of elements" in:
    var heap = new WeightedHeap[Int]()
    val elements = (1 to 1000).map(i => (i, (1000 - i).toLong)) // Element i with weight 1000 - i
    elements.foreach { case (value, weight) => heap = heap.insert(value, weight) }

    heap.size shouldEqual 1000
    heap.peekMin() shouldEqual Some((1000, 0L))

    for i <- 1000 to 1 by -1 do heap.extractMin().tap(mh => heap = mh._2)._1 shouldEqual Some((i, (1000 - i).toLong))

    heap.isEmpty shouldEqual true
end WeightedHeapTest
