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
