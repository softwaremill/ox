package ox.channels

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.*

import scala.concurrent.duration.*

class SelectOrClosedWithinTest extends AnyFlatSpec with Matchers:

  "selectOrClosedWithin" should "select a clause that can complete immediately" in supervised:
    val c1 = Channel.withCapacity[Int](10)
    val c2 = Channel.withCapacity[String](10)

    c1.send(42)

    val result = selectOrClosedWithin(100.millis, "timeout")(c1.receiveClause, c2.receiveClause)
    result should matchPattern:
      case c1.Received(42) =>

  it should "return timeout when no clause can complete within the timeout" in supervised:
    val c1 = Channel.rendezvous[Int]
    val c2 = Channel.rendezvous[String]

    val result = selectOrClosedWithin(10.millis, "timeout")(c1.receiveClause, c2.receiveClause)
    result shouldBe "timeout"

  it should "select a source that has a value immediately" in supervised:
    val s1 = Channel.withCapacity[Int](10)
    val s2 = Channel.withCapacity[String](10)

    s1.send(42)

    val result = selectOrClosedWithin(100.millis, "timeout")(s1, s2)
    result shouldBe 42

  it should "return timeout when no source has a value within the timeout" in supervised:
    val s1 = Channel.rendezvous[Int]
    val s2 = Channel.rendezvous[String]

    val result = selectOrClosedWithin(10.millis, "timeout")(s1, s2)
    result shouldBe "timeout"

  it should "work with different timeout value types" in supervised:
    val c1 = Channel.rendezvous[Int]

    // String timeout value
    selectOrClosedWithin(10.millis, "timeout")(c1.receiveClause) shouldBe "timeout"

    // Integer timeout value
    selectOrClosedWithin(10.millis, -1)(c1.receiveClause) shouldBe -1

    // Boolean timeout value
    selectOrClosedWithin(10.millis, false)(c1.receiveClause) shouldBe false

  it should "handle empty clauses sequence" in supervised:
    val result = selectOrClosedWithin(100.millis, "timeout")(Seq.empty[SelectClause[Int]])
    result shouldBe "timeout"

  it should "handle empty sources sequence" in supervised:
    val result = selectOrClosedWithin(100.millis, "timeout")(Seq.empty[Source[Int]])
    result shouldBe "timeout"

  "selectOrClosedWithin with single clause" should "complete when clause is ready" in supervised:
    // Given: A channel with data ready
    val c = Channel.withCapacity[Int](10)
    c.send(123)

    // When: Performing select with timeout
    val result = selectOrClosedWithin(100.millis, "timeout")(c.receiveClause)

    // Then: Should return immediate result without waiting for timeout
    result should matchPattern:
      case c.Received(123) =>

  it should "timeout when clause is not ready" in supervised:
    // Given: A channel with no data
    val c = Channel.rendezvous[Int]

    // When: Performing select with short timeout
    val result = selectOrClosedWithin(50.millis, "timeout")(c.receiveClause)

    // Then: Should return timeout result
    result shouldBe "timeout"

  "selectOrClosedWithin with multiple clauses" should "select the first ready clause" in supervised:
    // Given: Multiple channels, one with data ready
    val c1 = Channel.withCapacity[Int](10)
    val c2 = Channel.withCapacity[String](10)
    val c3 = Channel.withCapacity[Boolean](10)

    c1.send(42)
    c2.send("hello")

    // When: Performing select with timeout
    val result = selectOrClosedWithin(100.millis, "timeout")(
      c1.receiveClause,
      c2.receiveClause,
      c3.receiveClause
    )

    // Then: Should select c1 due to ordering bias
    result should matchPattern:
      case c1.Received(42) =>

  it should "timeout when no clauses are ready" in supervised:
    // Given: Multiple channels with no data
    val c1 = Channel.rendezvous[Int]
    val c2 = Channel.rendezvous[String]
    val c3 = Channel.rendezvous[Boolean]

    // When: Performing select with short timeout
    val result = selectOrClosedWithin(50.millis, "timeout")(
      c1.receiveClause,
      c2.receiveClause,
      c3.receiveClause
    )

    // Then: Should return timeout result
    result shouldBe "timeout"

  "selectOrClosedWithin with sources" should "select from ready source" in supervised:
    // Given: Multiple sources, one with data
    val s1 = Channel.withCapacity[Int](10)
    val s2 = Channel.withCapacity[String](10)

    s1.send(42)

    // When: Performing select with timeout
    val result = selectOrClosedWithin(100.millis, "timeout")(s1, s2)

    // Then: Should return the value from s1
    result shouldBe 42

  it should "timeout when no sources are ready" in supervised:
    // Given: Multiple sources with no data
    val s1 = Channel.rendezvous[Int]
    val s2 = Channel.rendezvous[String]

    // When: Performing select with short timeout
    val result = selectOrClosedWithin(50.millis, "timeout")(s1, s2)

    // Then: Should return timeout result
    result shouldBe "timeout"

  "selectOrClosedWithin error scenarios" should "handle channel closed with done" in supervised:
    // Given: A closed channel
    val c = Channel.withCapacity[Int](10)
    c.done()

    // When: Performing select with timeout
    val result = selectOrClosedWithin(100.millis, "timeout")(c.receiveClause)

    // Then: Should return channel closed result
    result should matchPattern:
      case _: ChannelClosed.Done.type =>

  it should "handle channel closed with error" in supervised:
    // Given: A channel with error
    val c = Channel.withCapacity[Int](10)
    val error = new RuntimeException("test error")
    c.error(error)

    // When: Performing select with timeout
    val result = selectOrClosedWithin(100.millis, "timeout")(c.receiveClause)

    // Then: Should return channel error result
    result should matchPattern:
      case ChannelClosed.Error(`error`) =>

  it should "prioritize ready channels over closed ones" in supervised:
    // Given: One ready channel and one closed channel
    val c1 = Channel.withCapacity[Int](10)
    val c2 = Channel.withCapacity[String](10)

    c1.send(42)
    c2.done()

    // When: Performing select with timeout
    val result = selectOrClosedWithin(100.millis, "timeout")(c1.receiveClause, c2.receiveClause)

    // Then: Should select the ready channel
    result should matchPattern:
      case c1.Received(42) =>

  "selectOrClosedWithin with different timeout types" should "work with various timeout value types" in supervised:
    // Given: A channel with no data
    val c = Channel.rendezvous[Int]

    // When/Then: Testing different timeout value types
    selectOrClosedWithin(10.millis, "string timeout")(c.receiveClause) shouldBe "string timeout"
    selectOrClosedWithin(10.millis, 999)(c.receiveClause) shouldBe 999
    selectOrClosedWithin(10.millis, true)(c.receiveClause) shouldBe true
    selectOrClosedWithin(10.millis, List(1, 2, 3))(c.receiveClause) shouldBe List(1, 2, 3)

    case class CustomTimeout(message: String)
    val customTimeout = CustomTimeout("operation timed out")
    selectOrClosedWithin(10.millis, customTimeout)(c.receiveClause) shouldBe customTimeout

  "selectOrClosedWithin with sequences" should "handle empty sequences" in supervised:
    // Given: Empty sequences

    // When/Then: Should return timeout immediately
    selectOrClosedWithin(100.millis, "timeout")(Seq.empty[SelectClause[Int]]) shouldBe "timeout"
    selectOrClosedWithin(100.millis, "timeout")(Seq.empty[Source[Int]]) shouldBe "timeout"

  it should "handle sequence of clauses" in supervised:
    // Given: A sequence of clauses
    val c1 = Channel.withCapacity[Int](10)
    val c2 = Channel.withCapacity[Int](10)
    val c3 = Channel.withCapacity[Int](10)

    c2.send(42)

    val clauses = Seq(c1.receiveClause, c2.receiveClause, c3.receiveClause)

    // When: Performing select with timeout
    val result = selectOrClosedWithin(100.millis, "timeout")(clauses)

    // Then: Should select from the ready clause
    result should matchPattern:
      case c2.Received(42) =>

  it should "handle sequence of sources" in supervised:
    // Given: A sequence of sources
    val s1 = Channel.withCapacity[Int](10)
    val s2 = Channel.withCapacity[Int](10)
    val s3 = Channel.withCapacity[Int](10)

    s3.send(99)

    val sources = Seq(s1, s2, s3)

    // When: Performing select with timeout
    val result = selectOrClosedWithin(100.millis, "timeout")(sources)

    // Then: Should select from the ready source
    result shouldBe 99

  "selectOrClosedWithin with various arities" should "work with all supported clause counts" in supervised:
    // When/Then: Testing different arities with fresh channels for each test

    // Single clause
    val c1a = Channel.withCapacity[Int](10)
    c1a.send(1)
    selectOrClosedWithin(100.millis, "timeout")(c1a.receiveClause) should matchPattern:
      case c1a.Received(1) =>

    // Two clauses
    val c1b = Channel.withCapacity[Int](10)
    val c2b = Channel.withCapacity[Int](10)
    c1b.send(1)
    selectOrClosedWithin(100.millis, "timeout")(c1b.receiveClause, c2b.receiveClause) should matchPattern:
      case c1b.Received(1) =>

    // Three clauses
    val c1c = Channel.withCapacity[Int](10)
    val c2c = Channel.withCapacity[Int](10)
    val c3c = Channel.withCapacity[Int](10)
    c1c.send(1)
    selectOrClosedWithin(100.millis, "timeout")(
      c1c.receiveClause,
      c2c.receiveClause,
      c3c.receiveClause
    ) should matchPattern:
      case c1c.Received(1) =>

    // Four clauses
    val c1d = Channel.withCapacity[Int](10)
    val c2d = Channel.withCapacity[Int](10)
    val c3d = Channel.withCapacity[Int](10)
    val c4d = Channel.withCapacity[Int](10)
    c1d.send(1)
    selectOrClosedWithin(100.millis, "timeout")(
      c1d.receiveClause,
      c2d.receiveClause,
      c3d.receiveClause,
      c4d.receiveClause
    ) should matchPattern:
      case c1d.Received(1) =>

    // Five clauses
    val c1e = Channel.withCapacity[Int](10)
    val c2e = Channel.withCapacity[Int](10)
    val c3e = Channel.withCapacity[Int](10)
    val c4e = Channel.withCapacity[Int](10)
    val c5e = Channel.withCapacity[Int](10)
    c1e.send(1)
    selectOrClosedWithin(100.millis, "timeout")(
      c1e.receiveClause,
      c2e.receiveClause,
      c3e.receiveClause,
      c4e.receiveClause,
      c5e.receiveClause
    ) should matchPattern:
      case c1e.Received(1) =>

  it should "work with all supported source counts" in supervised:
    // When/Then: Testing different arities with fresh sources for each test

    // Two sources
    val s1a = Channel.withCapacity[Int](10)
    val s2a = Channel.withCapacity[Int](10)
    s1a.send(1)
    selectOrClosedWithin(100.millis, "timeout")(s1a, s2a) shouldBe 1

    // Three sources
    val s1b = Channel.withCapacity[Int](10)
    val s2b = Channel.withCapacity[Int](10)
    val s3b = Channel.withCapacity[Int](10)
    s1b.send(1)
    selectOrClosedWithin(100.millis, "timeout")(s1b, s2b, s3b) shouldBe 1

    // Four sources
    val s1c = Channel.withCapacity[Int](10)
    val s2c = Channel.withCapacity[Int](10)
    val s3c = Channel.withCapacity[Int](10)
    val s4c = Channel.withCapacity[Int](10)
    s1c.send(1)
    selectOrClosedWithin(100.millis, "timeout")(s1c, s2c, s3c, s4c) shouldBe 1

    // Five sources
    val s1d = Channel.withCapacity[Int](10)
    val s2d = Channel.withCapacity[Int](10)
    val s3d = Channel.withCapacity[Int](10)
    val s4d = Channel.withCapacity[Int](10)
    val s5d = Channel.withCapacity[Int](10)
    s1d.send(1)
    selectOrClosedWithin(100.millis, "timeout")(s1d, s2d, s3d, s4d, s5d) shouldBe 1
end SelectOrClosedWithinTest
