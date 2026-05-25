package ox.channels

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.*

import scala.concurrent.TimeoutException
import scala.concurrent.duration.*

class SelectWithinTest extends AnyFlatSpec with Matchers:

  "selectWithin" should "select a clause that can complete immediately" in supervised:
    val c1 = Channel.withCapacity[Int](10)
    val c2 = Channel.withCapacity[String](10)

    c1.send(42)

    val result = selectWithin(100.millis)(c1.receiveClause, c2.receiveClause)
    result should matchPattern:
      case c1.Received(42) =>

  it should "throw TimeoutException when no clause can complete within the timeout" in supervised:
    val c1 = Channel.rendezvous[Int]
    val c2 = Channel.rendezvous[String]

    val exception = the[TimeoutException] thrownBy {
      selectWithin(10.millis)(c1.receiveClause, c2.receiveClause)
    }
    exception.getMessage should include("select timed out after 10 milliseconds")

  it should "select a source that has a value immediately" in supervised:
    val s1 = Channel.withCapacity[Int](10)
    val s2 = Channel.withCapacity[String](10)

    s1.send(42)

    val result = selectWithin(100.millis)(s1, s2)
    result shouldBe 42

  it should "throw TimeoutException when no source has a value within the timeout" in supervised:
    val s1 = Channel.rendezvous[Int]
    val s2 = Channel.rendezvous[String]

    val exception = the[TimeoutException] thrownBy {
      selectWithin(10.millis)(s1, s2)
    }
    exception.getMessage should include("select timed out after 10 milliseconds")

  it should "work with single clause" in supervised:
    val c1 = Channel.withCapacity[Int](10)
    c1.send(42)

    val result = selectWithin(100.millis)(c1.receiveClause)
    result should matchPattern:
      case c1.Received(42) =>

  it should "work with three clauses" in supervised:
    val c1 = Channel.withCapacity[Int](10)
    val c2 = Channel.withCapacity[String](10)
    val c3 = Channel.withCapacity[Boolean](10)

    c2.send("hello")

    val result = selectWithin(100.millis)(c1.receiveClause, c2.receiveClause, c3.receiveClause)
    result should matchPattern:
      case c2.Received("hello") =>

  it should "work with four clauses" in supervised:
    val c1 = Channel.withCapacity[Int](10)
    val c2 = Channel.withCapacity[String](10)
    val c3 = Channel.withCapacity[Boolean](10)
    val c4 = Channel.withCapacity[Double](10)

    c3.send(true)

    val result = selectWithin(100.millis)(c1.receiveClause, c2.receiveClause, c3.receiveClause, c4.receiveClause)
    result should matchPattern:
      case c3.Received(true) =>

  it should "work with five clauses" in supervised:
    val c1 = Channel.withCapacity[Int](10)
    val c2 = Channel.withCapacity[String](10)
    val c3 = Channel.withCapacity[Boolean](10)
    val c4 = Channel.withCapacity[Double](10)
    val c5 = Channel.withCapacity[Long](10)

    c4.send(3.14)

    val result = selectWithin(100.millis)(c1.receiveClause, c2.receiveClause, c3.receiveClause, c4.receiveClause, c5.receiveClause)
    result should matchPattern:
      case c4.Received(3.14) =>

  it should "work with sequence of clauses" in supervised:
    val c1 = Channel.withCapacity[Int](10)
    val c2 = Channel.withCapacity[Int](10)
    val c3 = Channel.withCapacity[Int](10)

    c2.send(42)

    val clauses = Seq(c1.receiveClause, c2.receiveClause, c3.receiveClause)
    val result = selectWithin(100.millis)(clauses)

    result should matchPattern:
      case c2.Received(42) =>

  "selectWithin with sources" should "work with single source" in supervised:
    val s1 = Channel.withCapacity[Int](10)
    s1.send(42)

    val result = selectWithin(100.millis)(s1)
    result shouldBe 42

  it should "work with two sources" in supervised:
    val s1 = Channel.withCapacity[Int](10)
    val s2 = Channel.withCapacity[String](10)

    s2.send("hello")

    val result = selectWithin(100.millis)(s1, s2)
    result shouldBe "hello"

  it should "work with three sources" in supervised:
    val s1 = Channel.withCapacity[Int](10)
    val s2 = Channel.withCapacity[String](10)
    val s3 = Channel.withCapacity[Boolean](10)

    s1.send(99)

    val result = selectWithin(100.millis)(s1, s2, s3)
    result shouldBe 99

  it should "work with four sources" in supervised:
    val s1 = Channel.withCapacity[Int](10)
    val s2 = Channel.withCapacity[String](10)
    val s3 = Channel.withCapacity[Boolean](10)
    val s4 = Channel.withCapacity[Double](10)

    s4.send(2.71)

    val result = selectWithin(100.millis)(s1, s2, s3, s4)
    result shouldBe 2.71

  it should "work with five sources" in supervised:
    val s1 = Channel.withCapacity[Int](10)
    val s2 = Channel.withCapacity[String](10)
    val s3 = Channel.withCapacity[Boolean](10)
    val s4 = Channel.withCapacity[Double](10)
    val s5 = Channel.withCapacity[Long](10)

    s5.send(1000L)

    val result = selectWithin(100.millis)(s1, s2, s3, s4, s5)
    result shouldBe 1000L

  it should "work with sequence of sources" in supervised:
    val s1 = Channel.withCapacity[Int](10)
    val s2 = Channel.withCapacity[Int](10)
    val s3 = Channel.withCapacity[Int](10)

    s3.send(123)

    val sources = Seq(s1, s2, s3)
    val result = selectWithin(100.millis)(sources)
    result shouldBe 123

  "selectWithin timeout scenarios" should "throw TimeoutException for single clause timeout" in supervised:
    val c1 = Channel.rendezvous[Int]

    val exception = the[TimeoutException] thrownBy {
      selectWithin(10.millis)(c1.receiveClause)
    }
    exception.getMessage should include("10 milliseconds")

  it should "throw TimeoutException for single source timeout" in supervised:
    val s1 = Channel.rendezvous[Int]

    val exception = the[TimeoutException] thrownBy {
      selectWithin(10.millis)(s1)
    }
    exception.getMessage should include("10 milliseconds")

  it should "throw TimeoutException for sequence of clauses timeout" in supervised:
    val c1 = Channel.rendezvous[Int]
    val c2 = Channel.rendezvous[Int]

    val clauses = Seq(c1.receiveClause, c2.receiveClause)
    val exception = the[TimeoutException] thrownBy {
      selectWithin(10.millis)(clauses)
    }
    exception.getMessage should include("10 milliseconds")

  it should "throw TimeoutException for sequence of sources timeout" in supervised:
    val s1 = Channel.rendezvous[Int]
    val s2 = Channel.rendezvous[Int]

    val sources = Seq(s1, s2)
    val exception = the[TimeoutException] thrownBy {
      selectWithin(10.millis)(sources)
    }
    exception.getMessage should include("10 milliseconds")

  it should "throw TimeoutException immediately for empty sequence of clauses" in supervised:
    val exception = the[TimeoutException] thrownBy {
      selectWithin(100.millis)(Seq.empty[SelectClause[Int]])
    }
    exception.getMessage should include("100 milliseconds")

  it should "throw TimeoutException immediately for empty sequence of sources" in supervised:
    val exception = the[TimeoutException] thrownBy {
      selectWithin(100.millis)(Seq.empty[Source[Int]])
    }
    exception.getMessage should include("100 milliseconds")

  "selectWithin error scenarios" should "throw ChannelClosedException when channel is closed with done" in supervised:
    val c = Channel.withCapacity[Int](10)
    c.done()

    val exception = the[ChannelClosedException] thrownBy {
      selectWithin(100.millis)(c.receiveClause)
    }
    exception shouldBe a[ChannelClosedException.Done]

  it should "throw ChannelClosedException when channel is closed with error" in supervised:
    val c = Channel.withCapacity[Int](10)
    val error = new RuntimeException("test error")
    c.error(error)

    val exception = the[ChannelClosedException] thrownBy {
      selectWithin(100.millis)(c.receiveClause)
    }
    exception should matchPattern:
      case ChannelClosedException.Error(`error`) =>

  it should "prioritize ready channels over closed ones" in supervised:
    val c1 = Channel.withCapacity[Int](10)
    val c2 = Channel.withCapacity[String](10)

    c1.send(42)
    c2.done()

    val result = selectWithin(100.millis)(c1.receiveClause, c2.receiveClause)
    result should matchPattern:
      case c1.Received(42) =>

  "selectWithin performance" should "not timeout when clause can complete immediately" in supervised:
    val start = System.currentTimeMillis()
    val c1 = Channel.withCapacity[Int](10)
    c1.send(42)

    val result = selectWithin(1.second)(c1.receiveClause)
    val duration = System.currentTimeMillis() - start

    result should matchPattern:
      case c1.Received(42) =>
    // Should complete much faster than the timeout
    duration should be < 100L

  it should "respect timeout duration" in supervised:
    val start = System.currentTimeMillis()
    val c1 = Channel.rendezvous[Int]

    try
      selectWithin(100.millis)(c1.receiveClause).discard
      fail("Should have thrown TimeoutException")
    catch
      case _: TimeoutException =>
        val duration = System.currentTimeMillis() - start
        // Should take approximately the timeout duration
        duration should be >= 100L
        duration should be < 200L
    end try

  "selectWithin with send clauses" should "work with send clauses" in supervised:
    val c1 = Channel.rendezvous[Int]
    val c2 = Channel.rendezvous[String]

    // Start a background task to receive from c1
    forkUnsupervised {
      sleep(50.millis)
      c1.receive()
    }.discard

    val result = selectWithin(200.millis)(c1.sendClause(42), c2.receiveClause)
    result should matchPattern:
      case c1.Sent() =>

  it should "throw TimeoutException when send clauses cannot complete" in supervised:
    val c1 = Channel.rendezvous[Int]
    val c2 = Channel.rendezvous[String]

    val exception = the[TimeoutException] thrownBy {
      selectWithin(10.millis)(c1.sendClause(42), c2.receiveClause)
    }
    exception.getMessage should include("10 milliseconds")
end SelectWithinTest
