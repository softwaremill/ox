package ox.channels

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

class SourceIOOpsTest extends AnyWordSpec with Matchers:
  import ox.IO.globalForTesting.given

  def inputStreamToString(is: InputStream)(using Ox): String = {
    val source = useInScope(scala.io.Source.fromInputStream(is))(_.close())
    source.mkString
  }

  "source.asInputStream" should {

    "return an empty InputStream for an empty source" in supervised {
      val source = Source.empty
      val stream = useInScope(source.asInputStream)(_.close())
      inputStreamToString(stream) shouldBe ""
    }

    "return an InputStream for a simple source" in supervised {
      val source = Source.fromValues(Chunk.fromArray("chunk1".getBytes), Chunk.fromArray("chunk2".getBytes))
      val stream = useInScope(source.asInputStream)(_.close())
      inputStreamToString(stream) shouldBe "chunk1chunk2"
    }

    "correctly track available bytes" in supervised {
      val source = Source.fromValues(Chunk.fromArray("chunk1".getBytes), Chunk.fromArray("chunk2".getBytes))
      val stream = useInScope(source.asInputStream)(_.close())
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
    }
  }

  "source.toOutputStream" should {

    def newOutputStream(failing: Boolean = false): TestOutputStream = new TestOutputStream(failing)

    "write a single chunk with bytes to an OutputStream" in supervised {
      val outputStream = newOutputStream()
      val sourceContent = "source.toOutputStream test1 content"
      val source = Source.fromValues(Chunk.fromArray(sourceContent.getBytes))
      assert(!outputStream.isClosed)
      source.toOutputStream(outputStream)
      outputStream.toString shouldBe sourceContent
      assert(outputStream.isClosed)
    }

    "write multiple chunks with bytes to an OutputStream" in supervised {
      val outputStream = newOutputStream()
      val sourceContent = "source.toOutputStream test2 content"
      val source = Source.fromValues(Chunk.fromArray(sourceContent.getBytes))
      assert(!outputStream.isClosed)
      source.toOutputStream(outputStream)
      outputStream.toString shouldBe sourceContent
      assert(outputStream.isClosed)
    }

    "handle an empty Source" in supervised {
      val outputStream = newOutputStream()
      val source = Source.empty[Chunk[Byte]]
      source.toOutputStream(outputStream)
      outputStream.toString shouldBe ""
      assert(outputStream.isClosed)
    }

    "close the OutputStream on write error" in supervised {
      val outputStream = newOutputStream(failing = true)
      val sourceContent = "source.toOutputStream test3 content"
      val source = Source.fromValues(Chunk.fromArray(sourceContent.getBytes))
      assert(!outputStream.isClosed)
      val exception = intercept[Exception](source.toOutputStream(outputStream))
      assert(outputStream.isClosed)
      exception.getMessage shouldBe "expected failed write"
    }

    "close the OutputStream on Source error" in supervised {
      val outputStream = newOutputStream()
      val source = Source
        .fromValues(Chunk.fromArray("initial content".getBytes))
        .concat(Source.failed(new Exception("expected source error")))
      assert(!outputStream.isClosed)
      val exception = intercept[Exception](source.toOutputStream(outputStream))
      exception.getMessage shouldBe "expected source error"
      assert(outputStream.isClosed)
    }
  }

  "source.toFile" should {

    "create a file and write a single chunk with bytes" in supervised {
      val path = useInScope(Files.createTempFile("ox", "test-writefile1"))(Files.deleteIfExists(_).discard)
      val sourceContent = "source.toFile test1 content"
      val source = Source.fromValues(Chunk.fromArray(sourceContent.getBytes))
      source.toFile(path)

      fileContent(path) shouldBe List(sourceContent)
    }

    "create a file and write multiple chunks with bytes" in supervised {
      val path = useInScope(Files.createTempFile("ox", "test-writefile2"))(Files.deleteIfExists(_).discard)
      val sourceContent = "source.toFile test2 content"
      val source = Source.fromIterable(sourceContent.grouped(4).toList.map(chunkStr => Chunk.fromArray(chunkStr.getBytes)))
      source.toFile(path)

      fileContent(path) shouldBe List(sourceContent)
    }

    "use an existing file and overwrite it a single chunk with bytes" in supervised {
      val path = useInScope(Files.createTempFile("ox", "test-writefile3"))(Files.deleteIfExists(_).discard)
      Files.write(path, "Some initial content".getBytes)
      val sourceContent = "source.toFile test3 content"
      val source = Source.fromValues(Chunk.fromArray(sourceContent.getBytes))
      source.toFile(path)

      fileContent(path) shouldBe List(sourceContent)
    }

    "handle an empty source" in supervised {
      val path = useInScope(Files.createTempFile("ox", "test-writefile4"))(Files.deleteIfExists(_).discard)
      val source = Source.empty[Chunk[Byte]]
      source.toFile(path)

      fileContent(path) shouldBe List.empty
    }

    "throw an exception on failing Source" in supervised {
      val path = useInScope(Files.createTempFile("ox", "test-writefile5"))(Files.deleteIfExists(_).discard)
      val source = Source
        .fromValues(Chunk.fromArray("initial content".getBytes))
        .concat(Source.failed(new Exception("expected source error")))
      val exception = intercept[Exception](source.toFile(path))
      exception.getMessage shouldBe "expected source error"
    }

    "throw an exception if path is a directory" in supervised {
      val path = Paths.get(getClass.getResource("/").toURI)
      val source = Source.fromValues(Chunk.empty[Byte])
      val exception = intercept[IOException](source.toFile(path))
      exception.getMessage should endWith("is a directory")
    }

    "throw an exception if file cannot be opened" in supervised {
      val path = Paths.get("/").resolve("/not-existing-directory/not-existing-file.txt")
      val source = Source.fromValues(Chunk.empty[Byte])
      assertThrows[NoSuchFileException](source.toFile(path))
    }

  }

  private def fileContent(path: Path)(using Ox): List[String] =
    Source.fromFile(path).toList.map(_.asStringUtf8)

class TestOutputStream(throwOnWrite: Boolean = false)(using IO) extends ByteArrayOutputStream:
  val closed: AtomicBoolean = new AtomicBoolean(false)

  override def close(): Unit =
    closed.set(true)
    super.close()

  override def write(bytes: Array[Byte]): Unit =
    if throwOnWrite then throw new Exception("expected failed write") else super.write(bytes)

  def isClosed: Boolean = closed.get
