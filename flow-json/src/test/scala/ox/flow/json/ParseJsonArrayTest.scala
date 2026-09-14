package ox.flow.json

import com.github.plokhotnyuk.jsoniter_scala.core.JsonReaderException
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.Chunk
import ox.discard
import ox.flow.Flow
import ox.timeout

import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*

class ParseJsonArrayTest extends AnyFlatSpec with Matchers:

  behavior of "parseJsonArray"

  it should "parse elements across every byte boundary, with multi-byte UTF-8 and surrounding whitespace" in:
    val input = oneByteChunks(" \n\t[{\"name\":\"東京\",\"age\":10},{\"name\":\"Málaga 🌊\",\"age\":20}]\r\n ")
    input.parseJsonArray[Person]().runToList() shouldBe List(Person("東京", 10), Person("Málaga 🌊", 20))

  it should "produce an empty flow for an empty array" in:
    byteFlow("[]").parseJsonArray[Person]().runToList() shouldBe Nil

  it should "accept a top-level null as an empty array" in:
    byteFlow("null").parseJsonArray[Person]().runToList() shouldBe Nil

  it should "fail on a non-array top-level value" in:
    a[JsonReaderException] should be thrownBy byteFlow("{\"name\":\"Ada\",\"age\":36}").parseJsonArray[Person]().runToList()

  it should "fail on an incomplete array" in:
    a[JsonReaderException] should be thrownBy byteFlow("[1").parseJsonArray[Int]().runToList()

  it should "fail on content after the array" in:
    a[JsonReaderException] should be thrownBy byteFlow("[1] true").parseJsonArray[Int]().runToList()

  it should "propagate an upstream error" in:
    val failure = new IllegalStateException("upstream failed")
    val input = Flow.concat(List(byteFlow("[1,"), Flow.failed[Chunk[Byte]](failure)))
    (the[IllegalStateException] thrownBy input.parseJsonArray[Int]().runToList()) shouldBe failure

  it should "emit elements before the source ends" in:
    val release = CountDownLatch(1)
    val input = Flow.usingEmit[Chunk[Byte]]: emit =>
      // jsoniter reads a few bytes past a number before it yields the value
      emit(Chunk.fromArray("[1,2,3,4,".getBytes(UTF_8)))
      release.await()
    try timeout(5.seconds)(input.parseJsonArray[Int]().take(2).runToList()) shouldBe List(1, 2)
    finally release.countDown() // in case the source wasn't interrupted

  it should "propagate a downstream failure and stop reading the source" in:
    val chunks = 10000
    val failure = new IllegalStateException("downstream failed")
    val emittedChunks = AtomicInteger()
    val input = Flow.usingEmit[Chunk[Byte]]: emit =>
      emit(Chunk.fromArray("[0".getBytes(UTF_8)))
      for _ <- 1 to chunks do
        emittedChunks.incrementAndGet().discard
        emit(Chunk.fromArray(",1".getBytes(UTF_8)))
      emit(Chunk.fromArray("]".getBytes(UTF_8)))

    val parsed = input.parseJsonArray[Int]().map(v => if v == 1 then throw failure else v)
    (the[IllegalStateException] thrownBy parsed.runToList()) shouldBe failure
    emittedChunks.get() should be < chunks

  it should "re-parse on each run" in:
    val flow = byteFlow("[1,2]").parseJsonArray[Int]()
    flow.take(1).runToList() shouldBe List(1)
    flow.runToList() shouldBe List(1, 2)
end ParseJsonArrayTest
