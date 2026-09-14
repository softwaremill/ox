package ox.flow.json

import com.github.plokhotnyuk.jsoniter_scala.core.JsonReaderException
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.Chunk
import ox.flow.Flow

import java.nio.charset.StandardCharsets.UTF_8
import scala.collection.mutable.ListBuffer

class ParseNdjsonTest extends AnyFlatSpec with Matchers:

  behavior of "parseNdjson"

  it should "parse records with a BOM, CRLF endings, blank lines and a final unterminated record" in:
    val input = byteFlow("﻿\n{\"name\":\"Ada\",\"age\":36}\r\n   \t\r\n{\"name\":\"Łukasz\",\"age\":41}")
    input.parseNdjson[Person]().runToList() shouldBe List(Person("Ada", 36), Person("Łukasz", 41))

  it should "parse across every byte boundary, including multi-byte UTF-8" in:
    val input = oneByteChunks("{\"name\":\"Zażółć 🦊\",\"age\":7}\n")
    input.parseNdjson[Person]().runToList() shouldBe List(Person("Zażółć 🦊", 7))

  it should "produce an empty flow for empty input" in:
    byteFlow("").parseNdjson[Person]().runToList() shouldBe Nil

  it should "frame across the backing arrays of one chunk" in:
    val chunk = Chunk.fromArray("1\n1".getBytes(UTF_8)) ++ Chunk.fromArray("2\n".getBytes(UTF_8))
    Flow.fromValues(chunk).parseNdjson[Int]().runToList() shouldBe List(1, 12)

  it should "fail on content after a record's value" in:
    val e = the[JsonReaderException] thrownBy byteFlow("{\"name\":\"Ada\",\"age\":36} true\n").parseNdjson[Person]().runToList()
    e.getMessage should include("expected end of input")

  it should "fail on a record over the limit, after emitting the earlier records" in:
    val emitted = ListBuffer.empty[Int]
    val input = Flow.fromValues(Chunk.fromArray("1\n12".getBytes(UTF_8)), Chunk.fromArray("34\n".getBytes(UTF_8)))
    val e = the[IllegalStateException] thrownBy input.parseNdjson[Int](maxRecordBytes = 3).runForeach(emitted.addOne)
    e.getMessage shouldBe "NDJSON record exceeds the maximum of 3 bytes"
    emitted.toList shouldBe List(1)

  it should "count a BOM towards the limit" in:
    byteFlow("﻿1\n").parseNdjson[Int](maxRecordBytes = 4).runToList() shouldBe List(1)
    an[IllegalStateException] should be thrownBy byteFlow("﻿1\n").parseNdjson[Int](maxRecordBytes = 3).runToList()

  it should "count a CR towards the limit" in:
    byteFlow("12\r\n").parseNdjson[Int](maxRecordBytes = 3).runToList() shouldBe List(12)
    an[IllegalStateException] should be thrownBy byteFlow("12\r\n").parseNdjson[Int](maxRecordBytes = 2).runToList()

  it should "reject a non-positive maxRecordBytes" in:
    an[IllegalArgumentException] should be thrownBy byteFlow("1\n").parseNdjson[Int](maxRecordBytes = 0)

  it should "propagate an upstream error" in:
    val failure = new IllegalStateException("upstream failed")
    val input = Flow.concat(List(byteFlow("1\n"), Flow.failed[Chunk[Byte]](failure)))
    (the[IllegalStateException] thrownBy input.parseNdjson[Int]().runToList()) shouldBe failure

  it should "re-frame from the start on each run" in:
    val flow = oneByteChunks("1\n2").parseNdjson[Int]()
    flow.take(1).runToList() shouldBe List(1)
    flow.runToList() shouldBe List(1, 2)
end ParseNdjsonTest
