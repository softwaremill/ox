package ox.channels

import org.scalatest.concurrent.Eventually.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import ox.{timeout as _, *}

import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.io.InputStream
import scala.concurrent.duration.*
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Paths
import java.io.IOException

class SourceCompanionIOOpsTest extends AnyWordSpec with Matchers:

  def emptyInputStream: TestStream = new TestStream("")
  def inputStream(text: String, failing: Boolean = false): TestStream = new TestStream(text, failing)

  def inputStreamToString(is: InputStream)(using Ox): String = {
    val source = useInScope(scala.io.Source.fromInputStream(is))(_.close())
    source.mkString
  }

  "Source.fromInputStream" should {

    "handle an empty InputStream" in supervised {
      Source.fromInputStream(emptyInputStream).toList shouldBe List.empty
    }

    "handle InputStream shorter than buffer size" in supervised {
      Source.fromInputStream(inputStream("abc")).toList.map(_.asString) shouldBe List("abc")
    }

    "handle InputStream longer than buffer size" in supervised {
      Source.fromInputStream(inputStream("some text"), chunkSize = 3).toList.map(_.asString) shouldBe List("som", "e t", "ext")
    }

    "close the InputStream after reading it" in supervised {
      val is = inputStream("abc")
      is.isClosed shouldBe false
      Source.fromInputStream(is).toList.discard
      eventually(timeout(5.seconds)) { is.isClosed shouldBe true }
    }

    "close the InputStream after failing with an exception" in supervised {
      val is = inputStream("abc", failing = true)
      is.isClosed shouldBe false
      assertThrows[Exception](Source.fromInputStream(is).toList.discard)
      eventually(timeout(5.seconds)) { is.isClosed shouldBe true }
    }
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

  "Source.fromFile" should {

    "read content from a file smaller than chunk size" in supervised {
      val path = useInScope(Files.createTempFile("ox", "test-readfile1"))(Files.deleteIfExists(_).discard)
      Files.write(path, "Test1 file content".getBytes)
      Source.fromFile(path).toList.map(_.asString) shouldBe List("Test1 file content")
    }

    "read content from a file larger than chunk size" in supervised {
      val path = useInScope(Files.createTempFile("ox", "test-readfile1"))(Files.deleteIfExists(_).discard)
      Files.write(path, "Test2 file content".getBytes)
      Source.fromFile(path, chunkSize = 3).toList.map(_.asString) shouldBe List("Tes", "t2 ", "fil", "e c", "ont", "ent")
    }

    "handle an empty file" in supervised {
      val path = useInScope(Files.createTempFile("ox", "test-readfile1"))(Files.deleteIfExists(_).discard)
      Source.fromFile(path).toList.map(_.asString) shouldBe List.empty
    }

    "throw an exception for missing file" in supervised {
      val path = Paths.get("/no/such/file.txt")
      assertThrows[NoSuchFileException](Source.fromFile(path))
    }

    "throw an exception if path is a directory" in supervised {
      val path = Paths.get(getClass.getResource("/").toURI)
      val exception = intercept[IOException](Source.fromFile(path))
      exception.getMessage should endWith("is a directory")
    }
  }

class TestStream(text: String, throwOnRead: Boolean = false) extends ByteArrayInputStream(text.getBytes):
  val closed: AtomicBoolean = new AtomicBoolean(false)

  override def close(): Unit =
    closed.set(true)
    super.close()

  override def read(a: Array[Byte]): Int =
    if throwOnRead then throw new Exception("expected failed read") else super.read(a)

  def isClosed: Boolean = closed.get
