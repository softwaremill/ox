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

class SourceIOOpsTest extends AnyWordSpec with Matchers:

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

  "source.toFile" should {

    "create a file and write a single chunk with bytes" in supervised {
      val path = Files.createTempFile("ox", "test-writefile1")
      val source = Source.fromValues(Chunk.fromArray("source.toFile test1 content".getBytes))
      source.toFile(path)

      Source.fromFile(path).toList.map(_.asString) shouldBe List("source.toFile test1 content")
    }

    "use an existing file and overwrite it a single chunk with bytes" in supervised {
      val path = Files.createTempFile("ox", "test-writefile2")
      Files.write(path, "Some initial content".getBytes)
      val source = Source.fromValues(Chunk.fromArray("source.toFile test2 content".getBytes))
      source.toFile(path)

      Source.fromFile(path).toList.map(_.asString) shouldBe List("source.toFile test2 content")
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
      val exception = assertThrows[NoSuchFileException](source.toFile(path))
    }
  }
