package ox

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ChunkTest extends AnyFlatSpec with Matchers:

  behavior of "Chunk"

  // ============================================================================
  // Creation Tests
  // ============================================================================

  it should "create empty chunks" in {
    val empty = Chunk.empty[Int]
    empty shouldBe Empty
    empty.length shouldBe 0
    empty.isEmpty shouldBe true
    empty.backingArrays shouldBe Nil
  }

  it should "create chunks from arrays" in {
    val chunk = Chunk.fromArray(Array(1, 2, 3, 4, 5))
    chunk shouldBe a[NonEmptyChunk[?]]
    chunk.length shouldBe 5
    chunk.toVector shouldBe Vector(1, 2, 3, 4, 5)
    chunk.backingArrays should have size 1
  }

  it should "create chunks from IArrays" in {
    val iarray = IArray(1, 2, 3, 4, 5)
    val chunk = Chunk.fromIArray(iarray)
    chunk shouldBe a[NonEmptyChunk[?]]
    chunk.length shouldBe 5
    chunk.backingArrays should have size 1
    chunk.backingArrays.head shouldBe iarray
  }

  it should "create chunks from elements" in {
    val chunk = Chunk(1, 2, 3, 4, 5)
    chunk shouldBe a[NonEmptyChunk[?]]
    chunk.length shouldBe 5
    chunk.toVector shouldBe Vector(1, 2, 3, 4, 5)
  }

  it should "create empty chunks from empty arrays" in {
    val chunk1 = Chunk.fromArray(Array.empty[Int])
    val chunk2 = Chunk.fromIArray(IArray.empty[Int])
    val chunk3 = Chunk[Int]()

    chunk1 shouldBe Empty
    chunk2 shouldBe Empty
    chunk3 shouldBe Empty
  }

  // ============================================================================
  // Basic Operations Tests
  // ============================================================================

  it should "support random access" in {
    val chunk = Chunk(1, 2, 3, 4, 5)
    chunk(0) shouldBe 1
    chunk(2) shouldBe 3
    chunk(4) shouldBe 5
  }

  it should "throw IndexOutOfBoundsException for invalid indices" in {
    val chunk = Chunk(1, 2, 3)
    intercept[IndexOutOfBoundsException] { chunk(-1) }.getMessage should include("NonEmptyChunk")
    intercept[IndexOutOfBoundsException] { chunk(3) }.getMessage should include("NonEmptyChunk")
    intercept[IndexOutOfBoundsException] { Empty(0) }.getMessage should include("Empty")
  }

  it should "support iteration" in {
    val chunk = Chunk(1, 2, 3, 4, 5)
    chunk.iterator.toVector shouldBe Vector(1, 2, 3, 4, 5)

    // Test iteration multiple times
    chunk.iterator.toVector shouldBe Vector(1, 2, 3, 4, 5)
    chunk.toList shouldBe List(1, 2, 3, 4, 5)
  }

  it should "support foreach operations" in {
    val chunk = Chunk(1, 2, 3, 4, 5)
    var sum = 0
    chunk.foreach(sum += _)
    sum shouldBe 15
  }

  // ============================================================================
  // Concatenation Tests
  // ============================================================================

  it should "concatenate two non-empty chunks efficiently" in {
    val chunk1 = Chunk(1, 2, 3)
    val chunk2 = Chunk(4, 5, 6)

    val result = chunk1 ++ chunk2
    result shouldBe a[NonEmptyChunk[?]]
    result.toVector shouldBe Vector(1, 2, 3, 4, 5, 6)
    result.length shouldBe 6

    // Should have two backing arrays
    result.backingArrays should have size 2
    result.backingArrays(0).toVector shouldBe Vector(1, 2, 3)
    result.backingArrays(1).toVector shouldBe Vector(4, 5, 6)
  }

  it should "handle concatenation with empty chunks" in {
    val chunk = Chunk(1, 2, 3)
    val empty = Chunk.empty[Int]

    chunk ++ empty shouldBe chunk
    empty ++ chunk shouldBe chunk
    empty ++ empty shouldBe empty
  }

  it should "support chained concatenation" in {
    val chunk1 = Chunk(1, 2)
    val chunk2 = Chunk(3, 4)
    val chunk3 = Chunk(5, 6)
    val chunk4 = Chunk(7, 8)

    val result = chunk1 ++ chunk2 ++ chunk3 ++ chunk4
    result.toVector shouldBe Vector(1, 2, 3, 4, 5, 6, 7, 8)
    result.backingArrays should have size 4
  }

  it should "concatenate chunks of different types" in {
    val intChunk = Chunk(1, 2, 3)
    val doubleChunk = Chunk(4.0, 5.0, 6.0)

    val result = intChunk ++ doubleChunk
    result.toVector shouldBe Vector(1.0, 2.0, 3.0, 4.0, 5.0, 6.0)
  }

  it should "concatenate non-empty chunk with non-chunk collections" in {
    val chunk = Chunk(1, 2, 3)
    val list = List(4, 5, 6)
    val vector = Vector(7, 8, 9)
    val array = Array(10, 11, 12)

    val result1 = chunk ++ list
    result1.toVector shouldBe Vector(1, 2, 3, 4, 5, 6)

    val result2 = chunk ++ vector
    result2.toVector shouldBe Vector(1, 2, 3, 7, 8, 9)

    val result3 = chunk ++ array
    result3.toVector shouldBe Vector(1, 2, 3, 10, 11, 12)
  }

  it should "concatenate empty chunk with non-chunk collections" in {
    val empty = Chunk.empty[Int]
    val list = List(1, 2, 3)
    val vector = Vector(4, 5, 6)
    val array = Array(7, 8, 9)

    val result1 = empty ++ list
    result1.toVector shouldBe Vector(1, 2, 3)

    val result2 = empty ++ vector
    result2.toVector shouldBe Vector(4, 5, 6)

    val result3 = empty ++ array
    result3.toVector shouldBe Vector(7, 8, 9)
  }

  it should "handle concatenation with empty collections" in {
    val chunk = Chunk(1, 2, 3)
    val empty = Chunk.empty[Int]
    val emptyList = List.empty[Int]
    val emptyVector = Vector.empty[Int]
    val emptyArray = Array.empty[Int]

    chunk ++ emptyList shouldBe chunk
    chunk ++ emptyVector shouldBe chunk
    chunk ++ emptyArray shouldBe chunk

    empty ++ emptyList shouldBe empty
    empty ++ emptyVector shouldBe empty
    empty ++ emptyArray shouldBe empty
  }

  // ============================================================================
  // Drop/Take Operations Tests
  // ============================================================================

  it should "support drop operations" in {
    val chunk = Chunk(1, 2, 3, 4, 5, 6, 7, 8)

    chunk.drop(0) shouldBe chunk
    chunk.drop(3).toVector shouldBe Vector(4, 5, 6, 7, 8)
    chunk.drop(8) shouldBe Chunk.empty
    chunk.drop(10) shouldBe Chunk.empty
    chunk.drop(-1) shouldBe chunk
  }

  it should "support take operations" in {
    val chunk = Chunk(1, 2, 3, 4, 5, 6, 7, 8)

    chunk.take(0) shouldBe Chunk.empty
    chunk.take(3).toVector shouldBe Vector(1, 2, 3)
    chunk.take(8) shouldBe chunk
    chunk.take(10) shouldBe chunk
    chunk.take(-1) shouldBe Chunk.empty
  }

  it should "handle drop/take on concatenated chunks" in {
    val chunk1 = Chunk(1, 2, 3, 4)
    val chunk2 = Chunk(5, 6, 7, 8)
    val concatenated = chunk1 ++ chunk2

    // Drop across array boundary
    concatenated.drop(3).toVector shouldBe Vector(4, 5, 6, 7, 8)
    concatenated.drop(5).toVector shouldBe Vector(6, 7, 8)

    // Take across array boundary
    concatenated.take(6).toVector shouldBe Vector(1, 2, 3, 4, 5, 6)
    concatenated.take(3).toVector shouldBe Vector(1, 2, 3)

    // Combined operations
    concatenated.drop(2).take(4).toVector shouldBe Vector(3, 4, 5, 6)
  }

  // ============================================================================
  // Transformation Tests
  // ============================================================================

  it should "support map operations" in {
    val chunk = Chunk(1, 2, 3, 4, 5)

    val mapped = chunk.map(_ * 2)
    mapped.toVector shouldBe Vector(2, 4, 6, 8, 10)
    mapped shouldBe a[Vector[?]] // map returns IndexedSeq, not Chunk
  }

  it should "support filter operations" in {
    val chunk = Chunk(1, 2, 3, 4, 5, 6)

    val filtered = chunk.filter(_ % 2 == 0)
    filtered.toVector shouldBe Vector(2, 4, 6)
  }

  it should "support collect operations" in {
    val chunk = Chunk(1, 2, 3, 4, 5)

    val collected = chunk.collect { case x if x % 2 == 0 => x * 10 }
    collected.toVector shouldBe Vector(20, 40)
  }

  // ============================================================================
  // Array Conversion Tests
  // ============================================================================

  it should "convert to arrays correctly" in {
    val chunk = Chunk(1, 2, 3, 4, 5)
    val array = chunk.toArray
    array should contain theSameElementsInOrderAs Array(1, 2, 3, 4, 5)
  }

  it should "convert concatenated chunks to arrays correctly" in {
    val chunk1 = Chunk(1, 2, 3)
    val chunk2 = Chunk(4, 5, 6)
    val concatenated = chunk1 ++ chunk2

    val array = concatenated.toArray
    array should contain theSameElementsInOrderAs Array(1, 2, 3, 4, 5, 6)
  }

  // ============================================================================
  // String Conversion Tests (for Byte chunks)
  // ============================================================================

  it should "convert byte chunks to strings" in {
    val bytes = "Hello, World!".getBytes("UTF-8")
    val chunk = Chunk.fromArray(bytes)

    chunk.asStringUtf8 shouldBe "Hello, World!"
    chunk.asString(java.nio.charset.StandardCharsets.UTF_8) shouldBe "Hello, World!"
  }

  it should "convert concatenated byte chunks to strings" in {
    val bytes1 = "Hello".getBytes("UTF-8")
    val bytes2 = ", ".getBytes("UTF-8")
    val bytes3 = "World!".getBytes("UTF-8")

    val chunk1 = Chunk.fromArray(bytes1)
    val chunk2 = Chunk.fromArray(bytes2)
    val chunk3 = Chunk.fromArray(bytes3)
    val concatenated = chunk1 ++ chunk2 ++ chunk3

    concatenated.asStringUtf8 shouldBe "Hello, World!"
  }

  // ============================================================================
  // Backing Arrays Access Tests
  // ============================================================================

  it should "provide access to backing arrays" in {
    val chunk1 = Chunk(1, 2, 3)
    val chunk2 = Chunk(4, 5, 6)
    val chunk3 = Chunk(7, 8, 9)

    // Single chunk should have one backing array
    chunk1.backingArrays should have size 1
    chunk1.backingArrays.head.toVector shouldBe Vector(1, 2, 3)

    // Concatenated chunks should preserve array structure
    val concatenated = chunk1 ++ chunk2 ++ chunk3
    concatenated.backingArrays should have size 3
    concatenated.backingArrays(0).toVector shouldBe Vector(1, 2, 3)
    concatenated.backingArrays(1).toVector shouldBe Vector(4, 5, 6)
    concatenated.backingArrays(2).toVector shouldBe Vector(7, 8, 9)
  }

  it should "allow efficient processing via backing arrays" in {
    val chunk1 = Chunk(1, 2, 3)
    val chunk2 = Chunk(4, 5, 6)
    val chunk3 = Chunk(7, 8, 9)
    val concatenated = chunk1 ++ chunk2 ++ chunk3

    // Process each backing array separately
    var sum = 0
    for array <- concatenated.backingArrays do for element <- array do sum += element

    sum shouldBe 45

    // This should be equivalent to normal iteration
    concatenated.sum shouldBe 45
  }

  // ============================================================================
  // Edge Cases and Error Handling Tests
  // ============================================================================

  it should "handle operations on empty chunks" in {
    val empty = Chunk.empty[Int]

    empty.drop(5) shouldBe empty
    empty.take(5) shouldBe empty
    empty.iterator.hasNext shouldBe false
    empty.toArray shouldBe Array.empty[Int]
    empty.indexWhere(_ => true) shouldBe -1
  }

  it should "maintain consistency between single and multi-array chunks" in {
    val single = Chunk(1, 2, 3, 4, 5, 6)
    val multi = Chunk(1, 2, 3).concat(Chunk(4, 5, 6))

    // Both should behave identically for all operations
    single.toVector shouldBe multi.toVector
    single.length shouldBe multi.length
    single.isEmpty shouldBe multi.isEmpty
    single.drop(2).toVector shouldBe multi.drop(2).toVector
    single.take(4).toVector shouldBe multi.take(4).toVector
    single.indexWhere(_ == 4) shouldBe multi.indexWhere(_ == 4)
    single.contains(3) shouldBe multi.contains(3)

    // Only difference should be backing array structure
    single.backingArrays should have size 1
    multi.backingArrays should have size 2
  }

  it should "handle large chunks efficiently" in {
    val largeArray = (1 to 10000).toArray
    val chunk = Chunk.fromArray(largeArray)

    chunk.length shouldBe 10000
    chunk(5000) shouldBe 5001
    chunk.drop(5000).take(100).toVector shouldBe (5001 to 5100).toVector
  }

  // ============================================================================
  // Search Operations Tests
  // ============================================================================

  it should "support indexWhere on single chunks" in {
    val chunk = Chunk(1, 2, 3, 4, 5, 6)

    // Basic searches from beginning
    chunk.indexWhere(_ == 1) shouldBe 0
    chunk.indexWhere(_ == 4) shouldBe 3
    chunk.indexWhere(_ == 6) shouldBe 5
    chunk.indexWhere(_ == 10) shouldBe -1

    // Searches with from parameter
    chunk.indexWhere(_ == 3, 0) shouldBe 2
    chunk.indexWhere(_ == 3, 2) shouldBe 2
    chunk.indexWhere(_ == 3, 3) shouldBe -1
    chunk.indexWhere(_ == 2, 3) shouldBe -1
    chunk.indexWhere(_ > 3, 0) shouldBe 3
    chunk.indexWhere(_ > 3, 4) shouldBe 4
    chunk.indexWhere(_ > 3, 6) shouldBe -1

    // Edge cases
    chunk.indexWhere(_ => true, -1) shouldBe 0 // Negative from should start at 0
    chunk.indexWhere(_ => true, 10) shouldBe -1 // From beyond length
    chunk.indexWhere(_ => false, 0) shouldBe -1 // Never matches
  }

  it should "support indexWhere on concatenated chunks" in {
    val chunk1 = Chunk(1, 2, 3)
    val chunk2 = Chunk(4, 5, 6)
    val chunk3 = Chunk(7, 8, 9)
    val concatenated = chunk1 ++ chunk2 ++ chunk3

    // Search across array boundaries
    concatenated.indexWhere(_ == 1) shouldBe 0 // First array
    concatenated.indexWhere(_ == 3) shouldBe 2 // End of first array
    concatenated.indexWhere(_ == 4) shouldBe 3 // Start of second array
    concatenated.indexWhere(_ == 6) shouldBe 5 // End of second array
    concatenated.indexWhere(_ == 7) shouldBe 6 // Start of third array
    concatenated.indexWhere(_ == 9) shouldBe 8 // End of third array
    concatenated.indexWhere(_ == 10) shouldBe -1 // Not found

    // Search with from parameter across boundaries
    concatenated.indexWhere(_ == 5, 0) shouldBe 4 // Found in second array
    concatenated.indexWhere(_ == 5, 4) shouldBe 4 // Found at exact position
    concatenated.indexWhere(_ == 5, 5) shouldBe -1 // Start search after position
    concatenated.indexWhere(_ > 5, 3) shouldBe 5 // First match after position 3
    concatenated.indexWhere(_ > 5, 6) shouldBe 6 // First match after position 6

    // Search starting from different arrays
    concatenated.indexWhere(_ > 3, 0) shouldBe 3 // Start of second array
    concatenated.indexWhere(_ > 6, 0) shouldBe 6 // Start of third array
    concatenated.indexWhere(_ > 6, 7) shouldBe 7 // Within third array
  }

  it should "handle indexWhere on empty chunks" in {
    val empty = Chunk.empty[Int]

    empty.indexWhere(_ => true) shouldBe -1
    empty.indexWhere(_ => true, 0) shouldBe -1
    empty.indexWhere(_ => true, 5) shouldBe -1
    empty.indexWhere(_ == 1) shouldBe -1
  }

  it should "handle indexWhere edge cases with concatenated chunks" in {
    val chunk1 = Chunk(1, 2)
    val chunk2 = Chunk.empty[Int]
    val chunk3 = Chunk(3, 4)
    val concatenated = chunk1 ++ chunk2 ++ chunk3

    // Should skip empty arrays correctly
    concatenated.indexWhere(_ == 3) shouldBe 2
    concatenated.indexWhere(_ == 4) shouldBe 3
    concatenated.indexWhere(_ > 2, 0) shouldBe 2
    concatenated.indexWhere(_ > 2, 2) shouldBe 2
    concatenated.indexWhere(_ > 2, 3) shouldBe 3
  }

  it should "support contains and exists operations" in {
    val chunk = Chunk(1, 2, 3, 4, 5)

    chunk.contains(3) shouldBe true
    chunk.contains(10) shouldBe false
    chunk.exists(_ > 3) shouldBe true
    chunk.exists(_ > 10) shouldBe false
    chunk.forall(_ > 0) shouldBe true
    chunk.forall(_ > 3) shouldBe false
  }

  // Helper extension for better test readability
  extension (i: Int) infix def foundAt(index: Int): Boolean = i == index
end ChunkTest
