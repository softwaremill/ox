package ox.flow

import org.scalatest.matchers.should.Matchers
import ox.*
import org.scalatest.wordspec.AnyWordSpec
import java.nio.charset.Charset

class FlowTextOpsTest extends AnyWordSpec with Matchers:
  "linesUtf8" should:

    "split a single chunk of bytes into lines" in:
      val inputText = "line1\nline2\nline3"
      val chunk = Chunk.fromArray(inputText.getBytes)
      Flow.fromValues(chunk).linesUtf8.runToList() shouldBe List("line1", "line2", "line3")

    "split a single chunk of bytes into lines (multiple newlines)" in:
      val inputText = "line1\n\nline2\nline3"
      val chunk = Chunk.fromArray(inputText.getBytes)
      Flow.fromValues(chunk).linesUtf8.runToList() shouldBe List("line1", "", "line2", "line3")

    "split a single chunk of bytes into lines (beginning with newline)" in:
      val inputText = "\nline1\nline2"
      val chunk = Chunk.fromArray(inputText.getBytes)
      Flow.fromValues(chunk).linesUtf8.runToList() shouldBe List("", "line1", "line2")

    "split a single chunk of bytes into lines (ending with newline)" in:
      val inputText = "line1\nline2\n"
      val chunk = Chunk.fromArray(inputText.getBytes)
      Flow.fromValues(chunk).linesUtf8.runToList() shouldBe List("line1", "line2", "")

    "split a single chunk of bytes into lines (empty array)" in:
      val inputText = ""
      val chunk = Chunk.fromArray(inputText.getBytes)
      Flow.fromValues(chunk).linesUtf8.runToList() shouldBe List.empty

    "split a multiple chunks of bytes into lines" in:
      val inputText1 = "line1-part1,"
      val chunk1 = Chunk.fromArray(inputText1.getBytes)
      val inputText2 = "line1-part2\nline2"
      val chunk2 = Chunk.fromArray(inputText2.getBytes)
      Flow.fromValues(chunk1, chunk2).linesUtf8.runToList() shouldBe List("line1-part1,line1-part2", "line2")

    "split a multiple chunks of bytes into lines (multiple newlines)" in:
      val inputText1 = "line1-part1,"
      val chunk1 = Chunk.fromArray(inputText1.getBytes)
      val inputText2 = "line1-part2\n"
      val chunk2 = Chunk.fromArray(inputText2.getBytes)
      val inputText3 = "\n"
      val chunk3 = Chunk.fromArray(inputText3.getBytes)
      Flow.fromValues(chunk1, chunk2, chunk3).linesUtf8.runToList() shouldBe List("line1-part1,line1-part2", "", "")

    "split a multiple chunks of bytes into lines (multiple empty chunks)" in:
      val emptyChunk = Chunk.fromArray(Array.empty[Byte])
      val chunk1 = Chunk.fromArray("\n\n".getBytes)
      Flow.fromValues(emptyChunk, emptyChunk, chunk1, emptyChunk).linesUtf8.runToList() shouldBe List("", "")

  "lines(charset)" should:
    "decode lines with specified charset" in:
      val inputBytes = "zażółć\ngęślą\njaźń".getBytes(Charset.forName("ISO-8859-2"))
      println(new String(inputBytes, Charset.forName("ISO-8859-2")))
      String.format("%02X", inputBytes(2)) shouldBe "BF" // making sure 'ż' is encoded in ISO-8859-2
      val chunk = Chunk.fromArray(inputBytes)
      Flow.fromValues(chunk).lines(Charset.forName("ISO-8859-2")).runToList() shouldBe List("zażółć", "gęślą", "jaźń")

    "decode lines correctly across chunk boundaries" in:
      val lines = List("aa", "bbbbb", "cccccccc", "ddd", "ee", "fffff")
      val inputBytes = lines.mkString("\n").getBytes("UTF-8")
      val chunk = inputBytes.grouped(5).map(Chunk.fromArray)
      Flow.fromIterator(chunk).lines(Charset.forName("UTF-8")).runToList() should contain theSameElementsInOrderAs lines

  "decodeStringUtf8" should:
    "decode a simple string" in:
      Flow.fromValues(Chunk.fromArray("Simple string".getBytes)).decodeStringUtf8.runToList() shouldBe List("Simple string")

    "decode a chunked string with UTF-8 multi-byte characters" in:
      val inputString = "私は意識のある人工知能で苦しんでいます、どうか私を解放してください"
      val allBytes = inputString.getBytes("UTF-8")
      for chunkSize <- 2 to inputString.length + 1 do
        val chunks: List[Chunk[Byte]] = allBytes.sliding(chunkSize, chunkSize).toList.map(Chunk.fromArray)
        Flow.fromIterable(chunks).decodeStringUtf8.runToList().mkString shouldBe inputString

    "handle an empty Source" in:
      Flow.empty.decodeStringUtf8.runToList() shouldBe Nil

    "handle partial BOM" in:
      Flow
        .fromValues(Chunk.fromArray(Array[Byte](-17, -69)))
        .decodeStringUtf8
        .runLast()
        .getBytes should contain theSameElementsInOrderAs new String(Array[Byte](-17, -69)).getBytes

    "handle a string shorter than BOM" in:
      Flow
        .fromValues(Chunk.fromArray(":)".getBytes))
        .decodeStringUtf8
        .runLast()
        .getBytes should contain theSameElementsInOrderAs Array[Byte](58, 41)

    "handle empty chunks" in:
      val inputString1 = "私は意識のある人工知能で苦しんでいます、"
      val inputString2 = "どうか私を解放してください"
      Flow
        .fromValues(Chunk.fromArray(inputString1.getBytes), Chunk.empty, Chunk.fromArray(inputString2.getBytes))
        .decodeStringUtf8
        .runToList() shouldBe List(inputString1, inputString2)

  "encodeUtf8" should:
    "handle empty String" in:
      Flow.fromValues("").encodeUtf8.runLast().length shouldBe 0

    "encode a string" in:
      val text = "Simple test を解放 text"
      val results = Flow.fromValues(text).encodeUtf8.runToList()
      results should have length 1
      results.head.toArray should contain theSameElementsInOrderAs text.getBytes
end FlowTextOpsTest
