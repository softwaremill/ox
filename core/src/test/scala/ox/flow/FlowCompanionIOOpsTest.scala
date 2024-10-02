package ox.flow

import org.scalatest.concurrent.Eventually.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import ox.{timeout as _, *}

import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Paths
import java.io.IOException

class FlowCompanionIOOpsTest extends AnyWordSpec with Matchers:
  def emptyInputStream: TestInputStream = new TestInputStream("")
  def inputStream(text: String, failing: Boolean = false): TestInputStream = new TestInputStream(text, failing)

  "fromInputStream" should:

    "handle an empty InputStream" in:
      Flow.fromInputStream(emptyInputStream).runToList() shouldBe List.empty

    "handle InputStream shorter than buffer size" in:
      toStrings(Flow.fromInputStream(inputStream("abc"))) shouldBe List("abc")

    "handle InputStream longer than buffer size" in:
      toStrings(Flow.fromInputStream(inputStream("some text"), chunkSize = 3)) shouldBe List("som", "e t", "ext")

    "close the InputStream after reading it" in:
      val is = inputStream("abc")
      is.isClosed shouldBe false
      Flow.fromInputStream(is).runToList().discard
      is.isClosed shouldBe true

    "close the InputStream after failing with an exception" in:
      val is = inputStream("abc", failing = true)
      is.isClosed shouldBe false
      assertThrows[Exception](Flow.fromInputStream(is).runToList().discard)
      is.isClosed shouldBe true

  "fromFile" should:

    "read content from a file smaller than chunk size" in supervised:
      val path = useInScope(Files.createTempFile("ox", "test-readfile1"))(Files.deleteIfExists(_).discard)
      Files.write(path, "Test1 file content".getBytes)
      toStrings(Flow.fromFile(path)) shouldBe List("Test1 file content")

    "read content from a file larger than chunk size" in supervised:
      val path = useInScope(Files.createTempFile("ox", "test-readfile1"))(Files.deleteIfExists(_).discard)
      Files.write(path, "Test2 file content".getBytes)
      toStrings(Flow.fromFile(path, chunkSize = 3)) shouldBe List("Tes", "t2 ", "fil", "e c", "ont", "ent")

    "handle an empty file" in supervised:
      val path = useInScope(Files.createTempFile("ox", "test-readfile1"))(Files.deleteIfExists(_).discard)
      toStrings(Flow.fromFile(path)) shouldBe List.empty

    "throw an exception for missing file" in supervised:
      val path = Paths.get("/no/such/file.txt")
      assertThrows[NoSuchFileException](Flow.fromFile(path).runToList())

    "throw an exception if path is a directory" in supervised:
      val path = Paths.get(getClass.getResource("/").toURI)
      val exception = intercept[IOException](Flow.fromFile(path).runToList())
      exception.getMessage should endWith("is a directory")

  private def toStrings(source: Flow[Chunk[Byte]]): List[String] = source.runToList().map(_.asStringUtf8)
end FlowCompanionIOOpsTest

class TestInputStream(text: String, throwOnRead: Boolean = false) extends ByteArrayInputStream(text.getBytes):
  val closed: AtomicBoolean = new AtomicBoolean(false)

  override def close(): Unit =
    closed.set(true)
    super.close()

  override def read(a: Array[Byte]): Int =
    if throwOnRead then throw new Exception("expected failed read") else super.read(a)

  def isClosed: Boolean = closed.get
end TestInputStream
