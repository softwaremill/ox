package ox.channels

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.*

class SourceTextOpsTest extends AnyFlatSpec with Matchers {
  behavior of "text pipe"

  it should "split a single chunk of bytes into lines" in supervised {
    val inputText = "line1\nline2\nline3"
    val chunk = Chunk.fromArray(inputText.getBytes)
    Source.fromValues(chunk).lines.toList shouldBe List("line1", "line2", "line3")
  }

  it should "split a single chunk of bytes into lines (multiple newlines)" in supervised {
    val inputText = "line1\n\nline2\nline3"
    val chunk = Chunk.fromArray(inputText.getBytes)
    Source.fromValues(chunk).lines.toList shouldBe List("line1", "", "line2", "line3")
  }

  it should "split a single chunk of bytes into lines (beginning with newline)" in supervised {
    val inputText = "\nline1\nline2"
    val chunk = Chunk.fromArray(inputText.getBytes)
    Source.fromValues(chunk).lines.toList shouldBe List("", "line1", "line2")
  }

  it should "split a single chunk of bytes into lines (ending with newline)" in supervised {
    val inputText = "line1\nline2\n"
    val chunk = Chunk.fromArray(inputText.getBytes)
    Source.fromValues(chunk).lines.toList shouldBe List("line1", "line2", "")
  }

  it should "split a single chunk of bytes into lines (empty array)" in supervised {
    val inputText = ""
    val chunk = Chunk.fromArray(inputText.getBytes)
    Source.fromValues(chunk).lines.toList shouldBe List.empty
  }

  it should "split a multiple chunks of bytes into lines" in supervised {
    val inputText1 = "line1-part1,"
    val chunk1 = Chunk.fromArray(inputText1.getBytes)
    val inputText2 = "line1-part2\nline2"
    val chunk2 = Chunk.fromArray(inputText2.getBytes)
    Source.fromValues(chunk1, chunk2).lines.toList shouldBe List("line1-part1,line1-part2", "line2")
  }

  it should "split a multiple chunks of bytes into lines (multiple newlines)" in supervised {
    val inputText1 = "line1-part1,"
    val chunk1 = Chunk.fromArray(inputText1.getBytes)
    val inputText2 = "line1-part2\n"
    val chunk2 = Chunk.fromArray(inputText2.getBytes)
    val inputText3 = "\n"
    val chunk3 = Chunk.fromArray(inputText3.getBytes)
    Source.fromValues(chunk1, chunk2, chunk3).lines.toList shouldBe List("line1-part1,line1-part2", "", "")
  }

  it should "split a multiple chunks of bytes into lines (multiple empty chunks)" in supervised {
    val emptyChunk = Chunk.fromArray(Array.empty[Byte])
    val chunk1 = Chunk.fromArray("\n\n".getBytes)
    Source.fromValues(emptyChunk, emptyChunk, chunk1, emptyChunk).lines.toList shouldBe List("", "")
  }
}
