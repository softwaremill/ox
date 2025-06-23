package ox.flow

import org.scalatest.concurrent.Eventually.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import ox.{timeout as _, *}

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicBoolean

class FlowIOOpsTest extends AnyWordSpec with Matchers:
  def inputStreamToString(is: InputStream)(using Ox): String =
    val source = useInScope(scala.io.Source.fromInputStream(is))(_.close())
    source.mkString

  "asInputStream" should:

    "return an empty InputStream for an empty source" in supervised:
      val source = Flow.empty
      val stream = useInScope(source.runToInputStream())(_.close())
      inputStreamToString(stream) shouldBe ""

    "return an InputStream for a simple source" in supervised:
      val source = Flow.fromValues(Chunk.fromArray("chunk1".getBytes), Chunk.fromArray("chunk2".getBytes))
      val stream = useInScope(source.runToInputStream())(_.close())
      inputStreamToString(stream) shouldBe "chunk1chunk2"

    "correctly track available bytes" in supervised:
      val source = Flow.fromValues(Chunk.fromArray("chunk1".getBytes), Chunk.fromArray("chunk2".getBytes))
      val stream = useInScope(source.runToInputStream())(_.close())
      stream.available shouldBe 0
      stream.read().discard
      stream.available shouldBe 5
      stream.readNBytes(5).discard
      stream.available shouldBe 0
      stream.read().discard
      stream.read().discard
      stream.available shouldBe 4
      stream.readNBytes(5).discard
      stream.available shouldBe 0

    "support bulk read operations with read(byte[])" in supervised:
      val content = "Hello, World! This is a test for bulk reading operations."
      val source = Flow.fromValues(Chunk.fromArray(content.getBytes))
      val stream = useInScope(source.runToInputStream())(_.close())

      val buffer = new Array[Byte](20)
      val bytesRead = stream.read(buffer)
      bytesRead shouldBe 20
      new String(buffer) shouldBe "Hello, World! This i"

      val buffer2 = new Array[Byte](100)
      val bytesRead2 = stream.read(buffer2)
      bytesRead2 shouldBe (content.length - 20)
      new String(buffer2, 0, bytesRead2) shouldBe "s a test for bulk reading operations."

    "handle bulk read operations across multiple chunks" in supervised:
      val chunk1 = "Hello, "
      val chunk2 = "World! "
      val chunk3 = "This is a test."
      val source = Flow.fromValues(
        Chunk.fromArray(chunk1.getBytes),
        Chunk.fromArray(chunk2.getBytes),
        Chunk.fromArray(chunk3.getBytes)
      )
      val stream = useInScope(source.runToInputStream())(_.close())

      // Read across chunk boundaries
      val buffer = new Array[Byte](12)
      val bytesRead = stream.read(buffer)
      bytesRead shouldBe 12
      new String(buffer) shouldBe "Hello, World"

      // Read the rest
      val remainingContent = "! This is a test."
      val buffer2 = new Array[Byte](100)
      val bytesRead2 = stream.read(buffer2)
      bytesRead2 shouldBe remainingContent.length
      new String(buffer2, 0, bytesRead2) shouldBe remainingContent

    "handle bulk read with concatenated chunks (multiple backing arrays)" in supervised:
      val chunk1 = Chunk.fromArray("Part1".getBytes)
      val chunk2 = Chunk.fromArray("-Part2".getBytes)
      val chunk3 = Chunk.fromArray("-Part3".getBytes)
      // Create a chunk with multiple backing arrays through concatenation
      val concatenatedChunk = chunk1 ++ chunk2 ++ chunk3
      val source = Flow.fromValues(concatenatedChunk)
      val stream = useInScope(source.runToInputStream())(_.close())

      // Read across backing array boundaries
      val buffer = new Array[Byte](8)
      val bytesRead = stream.read(buffer)
      bytesRead shouldBe 8
      new String(buffer) shouldBe "Part1-Pa"

      val buffer2 = new Array[Byte](10)
      val bytesRead2 = stream.read(buffer2)
      bytesRead2 shouldBe 9 // "rt2-Part3"
      new String(buffer2, 0, bytesRead2) shouldBe "rt2-Part3"

    "handle read(byte[], offset, length) with various parameters" in supervised:
      val content = "0123456789ABCDEFGHIJ"
      val source = Flow.fromValues(Chunk.fromArray(content.getBytes))
      val stream = useInScope(source.runToInputStream())(_.close())

      val buffer = new Array[Byte](30)

      // Read with offset and limited length
      val bytesRead1 = stream.read(buffer, 5, 10)
      bytesRead1 shouldBe 10
      new String(buffer, 5, 10) shouldBe "0123456789"

      // Read remaining data with different offset
      val bytesRead2 = stream.read(buffer, 15, 10)
      bytesRead2 shouldBe 10 // "ABCDEFGHIJ"
      new String(buffer, 15, 10) shouldBe "ABCDEFGHIJ"

      // Try to read more when stream is exhausted
      val bytesRead3 = stream.read(buffer, 0, 10)
      bytesRead3 shouldBe -1

    "handle edge cases for read(byte[], offset, length)" in supervised:
      val content = "test"
      val source = Flow.fromValues(Chunk.fromArray(content.getBytes))
      val stream = useInScope(source.runToInputStream())(_.close())

      val buffer = new Array[Byte](10)

      // Zero length read should return 0
      stream.read(buffer, 0, 0) shouldBe 0

      // Normal read should still work
      stream.read(buffer, 0, 4) shouldBe 4
      new String(buffer, 0, 4) shouldBe "test"

    "throw appropriate exceptions for invalid read parameters" in supervised:
      val source = Flow.fromValues(Chunk.fromArray("test".getBytes))
      val stream = useInScope(source.runToInputStream())(_.close())
      val buffer = new Array[Byte](10)

      // Null buffer
      intercept[NullPointerException](stream.read(null, 0, 5)).discard

      // Negative offset
      intercept[IndexOutOfBoundsException](stream.read(buffer, -1, 5)).discard

      // Negative length
      intercept[IndexOutOfBoundsException](stream.read(buffer, 0, -1)).discard

      // Length exceeds buffer capacity
      intercept[IndexOutOfBoundsException](stream.read(buffer, 5, 10)).discard

    "maintain consistency between single-byte and bulk reads" in supervised:
      val content = "Consistency test for mixed read operations"
      val source1 = Flow.fromValues(Chunk.fromArray(content.getBytes))
      val source2 = Flow.fromValues(Chunk.fromArray(content.getBytes))

      val stream1 = useInScope(source1.runToInputStream())(_.close())
      val stream2 = useInScope(source2.runToInputStream())(_.close())

      // Read using single-byte operations
      val singleByteResult = new StringBuilder
      var byte = stream1.read()
      while byte != -1 do
        singleByteResult.append(byte.toChar)
        byte = stream1.read()

      // Read using bulk operations
      val bulkResult = new StringBuilder
      val buffer = new Array[Byte](10)
      var bytesRead = stream2.read(buffer)
      while bytesRead != -1 do
        bulkResult.append(new String(buffer, 0, bytesRead))
        bytesRead = stream2.read(buffer)

      singleByteResult.toString shouldBe content
      bulkResult.toString shouldBe content
      singleByteResult.toString shouldBe bulkResult.toString

    "handle chunks with empty backing arrays" in supervised:
      // Create chunks with empty arrays mixed with non-empty ones
      val chunk1 = Chunk.fromArray("Hello".getBytes)
      val emptyChunk1 = Chunk.empty[Byte]
      val chunk2 = Chunk.fromArray(" ".getBytes)
      val emptyChunk2 = Chunk.empty[Byte]
      val chunk3 = Chunk.fromArray("World".getBytes)
      val emptyChunk3 = Chunk.empty[Byte]

      // Create a concatenated chunk that will have empty arrays in backingArrays
      val concatenatedChunk = chunk1 ++ emptyChunk1 ++ chunk2 ++ emptyChunk2 ++ chunk3 ++ emptyChunk3
      val source = Flow.fromValues(concatenatedChunk)
      val stream = useInScope(source.runToInputStream())(_.close())

      // Verify that empty arrays are properly skipped
      val buffer1 = new Array[Byte](3)
      stream.read(buffer1) shouldBe 3
      new String(buffer1) shouldBe "Hel"

      val buffer2 = new Array[Byte](4)
      stream.read(buffer2) shouldBe 4
      new String(buffer2) shouldBe "lo W"

      val buffer3 = new Array[Byte](4)
      stream.read(buffer3) shouldBe 4
      new String(buffer3) shouldBe "orld"

      // Should be at end of stream
      stream.read() shouldBe -1

    "handle flow with only empty chunks" in supervised:
      val source = Flow.fromValues(
        Chunk.empty[Byte],
        Chunk.empty[Byte],
        Chunk.empty[Byte]
      )
      val stream = useInScope(source.runToInputStream())(_.close())

      // Should immediately return -1 since there's no actual data
      stream.read() shouldBe -1

      val buffer = new Array[Byte](10)
      stream.read(buffer) shouldBe -1

    "handle mixed empty and non-empty chunks in flow" in supervised:
      val source = Flow.fromValues(
        Chunk.empty[Byte],
        Chunk.fromArray("Start".getBytes),
        Chunk.empty[Byte],
        Chunk.empty[Byte],
        Chunk.fromArray("-Middle-".getBytes),
        Chunk.empty[Byte],
        Chunk.fromArray("End".getBytes),
        Chunk.empty[Byte]
      )
      val stream = useInScope(source.runToInputStream())(_.close())

      val buffer = new Array[Byte](50)
      val bytesRead = stream.read(buffer)
      bytesRead shouldBe 16 // "Start-Middle-End".length is actually 16, not 17
      new String(buffer, 0, bytesRead) shouldBe "Start-Middle-End"

      // Should be at end of stream
      stream.read() shouldBe -1

  "toOutputStream" should:

    def newOutputStream(failing: Boolean = false): TestOutputStream = new TestOutputStream(failing)

    "write a single chunk with bytes to an OutputStream" in:
      val outputStream = newOutputStream()
      val sourceContent = "source.toOutputStream test1 content"
      val source = Flow.fromValues(Chunk.fromArray(sourceContent.getBytes))
      assert(!outputStream.isClosed)
      source.runToOutputStream(outputStream)
      outputStream.toString shouldBe sourceContent
      assert(outputStream.isClosed)

    "write multiple chunks with bytes to an OutputStream" in:
      val outputStream = newOutputStream()
      val sourceContent = "source.toOutputStream test2 content"
      val source = Flow.fromValues(Chunk.fromArray(sourceContent.getBytes))
      assert(!outputStream.isClosed)
      source.runToOutputStream(outputStream)
      outputStream.toString shouldBe sourceContent
      assert(outputStream.isClosed)

    "write concatenated chunks to an OutputStream" in:
      val outputStream = newOutputStream()
      val sourceContent = "source.toOutputStream concatenated chunks test"
      // Split the content into chunks of 4 characters each to test multiple chunks
      val chunks = sourceContent.grouped(4).toList.map(chunkStr => Chunk.fromArray(chunkStr.getBytes))
      val source = Flow.fromIterable(chunks)
      assert(!outputStream.isClosed)
      source.runToOutputStream(outputStream)
      outputStream.toString shouldBe sourceContent
      assert(outputStream.isClosed)

    "handle an empty Source" in:
      val outputStream = newOutputStream()
      val source = Flow.empty[Chunk[Byte]]
      source.runToOutputStream(outputStream)
      outputStream.toString shouldBe ""
      assert(outputStream.isClosed)

    "close the OutputStream on write error" in:
      val outputStream = newOutputStream(failing = true)
      val sourceContent = "source.toOutputStream test3 content"
      val source = Flow.fromValues(Chunk.fromArray(sourceContent.getBytes))
      assert(!outputStream.isClosed)
      val exception = intercept[Exception](source.runToOutputStream(outputStream))
      assert(outputStream.isClosed)
      exception.getMessage shouldBe "expected failed write"

    "close the OutputStream on error" in:
      val outputStream = newOutputStream()
      val source = Flow
        .fromValues(Chunk.fromArray("initial content".getBytes))
        .concat(Flow.failed(new Exception("expected source error")))
      assert(!outputStream.isClosed)
      val exception = intercept[Exception](source.runToOutputStream(outputStream))
      exception.getMessage shouldBe "expected source error"
      assert(outputStream.isClosed)

  "toFile" should:

    "open existing file and write a single chunk with bytes" in supervised:
      val path = useInScope(Files.createTempFile("ox", "test-writefile1"))(Files.deleteIfExists(_).discard)
      val sourceContent = "source.toFile test1 content"
      val source = Flow.fromValues(Chunk.fromArray(sourceContent.getBytes))
      source.runToFile(path)

      fileContent(path) shouldBe List(sourceContent)

    "open existing file and write multiple chunks with bytes" in supervised:
      val path = useInScope(Files.createTempFile("ox", "test-writefile2"))(Files.deleteIfExists(_).discard)
      val sourceContent = "source.toFile test2 content"
      val source = Flow.fromIterable(sourceContent.grouped(4).toList.map(chunkStr => Chunk.fromArray(chunkStr.getBytes)))
      source.runToFile(path)

      fileContent(path) shouldBe List(sourceContent)

    "create file and write multiple chunks with bytes" in supervised:
      var filePath = null: Path
      val folderPath = useInScope(Files.createTempDirectory("ox")) { path =>
        Files.deleteIfExists(filePath)
        Files.deleteIfExists(path).discard
      }
      val sourceContent = "source.toFile test3 content"
      filePath = folderPath.resolve("test-writefile3")
      Flow
        .fromIterable(sourceContent.grouped(4).toList.map(chunkStr => Chunk.fromArray(chunkStr.getBytes)))
        .runToFile(filePath)

      fileContent(filePath) shouldBe List(sourceContent)

    "write concatenated chunks to a file" in supervised:
      val path = useInScope(Files.createTempFile("ox", "test-writefile-concat"))(Files.deleteIfExists(_).discard)
      val sourceContent = "source.toFile concatenated chunks test"
      // Split the content into chunks of 4 characters each to test multiple chunks
      val chunks = sourceContent.grouped(4).toList.map(chunkStr => Chunk.fromArray(chunkStr.getBytes))
      val source = Flow.fromIterable(chunks)
      source.runToFile(path)

      fileContent(path) shouldBe List(sourceContent)

    "use an existing file and overwrite it a single chunk with bytes" in supervised:
      val path = useInScope(Files.createTempFile("ox", "test-writefile3"))(Files.deleteIfExists(_).discard)
      Files.write(path, "Some initial content".getBytes)
      val sourceContent = "source.toFile test3 content"
      val source = Flow.fromValues(Chunk.fromArray(sourceContent.getBytes))
      source.runToFile(path)

      fileContent(path) shouldBe List(sourceContent)

    "handle an empty source" in supervised:
      val path = useInScope(Files.createTempFile("ox", "test-writefile4"))(Files.deleteIfExists(_).discard)
      val source = Flow.empty[Chunk[Byte]]
      source.runToFile(path)

      fileContent(path) shouldBe List.empty

    "throw an exception on failing Source" in supervised:
      val path = useInScope(Files.createTempFile("ox", "test-writefile5"))(Files.deleteIfExists(_).discard)
      val source = Flow
        .fromValues(Chunk.fromArray("initial content".getBytes))
        .concat(Flow.failed(new Exception("expected source error")))
      val exception = intercept[Exception](source.runToFile(path))
      exception.getMessage shouldBe "expected source error"

    "throw an exception if path is a directory" in:
      val path = Paths.get(getClass.getResource("/").toURI)
      val source = Flow.fromValues(Chunk.empty[Byte])
      val exception = intercept[IOException](source.runToFile(path))
      exception.getMessage should endWith("is a directory")

    "throw an exception if file cannot be opened" in:
      val path = Paths.get("/").resolve("/directory-without-permissions/file-without-access.txt")
      val source = Flow.fromValues(Chunk.empty[Byte])
      assertThrows[NoSuchFileException](source.runToFile(path))

  private def fileContent(path: Path)(using Ox): List[String] = Flow.fromFile(path).runToList().map(_.asStringUtf8)
end FlowIOOpsTest

class TestOutputStream(throwOnWrite: Boolean = false) extends ByteArrayOutputStream:
  val closed: AtomicBoolean = new AtomicBoolean(false)

  override def close(): Unit =
    closed.set(true)
    super.close()

  override def write(bytes: Array[Byte]): Unit =
    if throwOnWrite then throw new Exception("expected failed write") else super.write(bytes)

  def isClosed: Boolean = closed.get
end TestOutputStream
