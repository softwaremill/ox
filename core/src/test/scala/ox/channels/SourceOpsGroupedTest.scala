package ox.channels

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration.{DurationInt, FiniteDuration}
import ox.*

import scala.util.{Success, Try}

class SourceOpsGroupedTest extends AnyFlatSpec with Matchers {
  behavior of "SourceOps.grouped"

  it should "emit grouped elements" in supervised {
    Source.fromValues(1, 2, 3, 4, 5, 6).grouped(3).toList shouldBe List(List(1, 2, 3), List(4, 5, 6))
  }

  it should "emit grouped elements and include remaining values when channel closes" in supervised {
    Source.fromValues(1, 2, 3, 4, 5, 6, 7).grouped(3).toList shouldBe List(List(1, 2, 3), List(4, 5, 6), List(7))
  }

  it should "return failed source when the original source is failed" in supervised {
    val failure = new RuntimeException()
    Source.failed(failure).grouped(3).receiveOrClosed() shouldBe ChannelClosed.Error(failure)
  }

  behavior of "SourceOps.groupedWeighted"

  it should "emit grouped elements with custom cost function" in supervised {
    Source.fromValues(1, 2, 3, 4, 5, 6, 5, 3, 1).groupedWeighted(10)(n => n * 2).toList shouldBe List(
      List(1, 2, 3),
      List(4, 5),
      List(6),
      List(5),
      List(3, 1)
    )
  }

  it should "return failed source when cost function throws exception" in supervised {
    val source = Source.fromValues(1, 2, 3, 0, 4, 5, 6, 7).groupedWeighted(150)(n => 100 / n)
    source.receiveOrClosed() shouldBe Seq(1, 2)
    source.receiveOrClosed() should matchPattern {
      case ChannelClosed.Error(reason) if reason.isInstanceOf[ArithmeticException] =>
    }
  }

  it should "return failed source when the original source is failed" in supervised {
    val failure = new RuntimeException()
    Source.failed[Long](failure).groupedWeighted(10)(n => n * 2).receiveOrClosed() shouldBe ChannelClosed.Error(failure)
  }

  behavior of "SourceOps.groupedWithin"

  it should "group first batch of elements due to limit and second batch due to timeout" in supervised {
    val c = StageCapacity.newChannel[Int]
    val start = System.nanoTime()
    fork {
      c.send(1)
      c.send(2)
      c.send(3)
      sleep(50.millis)
      c.send(4)
      sleep(200.millis) // to ensure the timeout is executed before the channel closes
      c.done()
    }
    val elementsWithEmittedTimeOffset =
      c.groupedWithin(3, 100.millis).map(s => (s, FiniteDuration(System.nanoTime() - start, "nanos"))).toList

    elementsWithEmittedTimeOffset.map(_._1) shouldBe List(List(1, 2, 3), List(4))
    // first batch is emitted immediately as it fills up
    elementsWithEmittedTimeOffset(0)._2 should be < 50.millis
    // second batch is emitted after 100ms timeout after 50ms sleep after the first batch
    elementsWithEmittedTimeOffset(1)._2 should be >= 100.millis
  }

  it should "group first batch of elements due to timeout and second batch due to limit" in supervised {
    val c = StageCapacity.newChannel[Int]
    val start = System.nanoTime()
    fork {
      c.send(1)
      c.send(2)
      sleep(150.millis)
      c.send(3)
      c.send(4)
      c.send(5)
      c.done()
    }
    val elementsWithEmittedTimeOffset = c
      .groupedWithin(3, 100.millis)
      .map(s => (s, FiniteDuration(System.nanoTime() - start, "nanos")))
      .toList

    elementsWithEmittedTimeOffset.map(_._1) shouldBe List(List(1, 2), List(3, 4, 5))
    // first batch is emitted after 100ms timeout
    elementsWithEmittedTimeOffset(0)._2 should (be >= 100.millis and be < 150.millis)
    // second batch is emitted immediately after 200ms sleep
    elementsWithEmittedTimeOffset(1)._2 should be >= 150.millis
  }

  it should "wake up on new element and send it immediately after first batch is sent and channel goes to time-out mode" in supervised {
    val c = StageCapacity.newChannel[Int]
    val start = System.nanoTime()
    fork {
      c.send(1)
      c.send(2)
      c.send(3)
      sleep(200.millis)
      c.send(3)
      sleep(200.millis) // to ensure the timeout is executed before the channel closes
      c.done()
    }
    val elementsWithEmittedTimeOffset = c
      .groupedWithin(3, 100.millis)
      .map(s => (s, FiniteDuration(System.nanoTime() - start, "nanos")))
      .toList

    elementsWithEmittedTimeOffset.map(_._1) shouldBe List(List(1, 2, 3), List(3))
    // first batch is emitted immediately as it fills up
    elementsWithEmittedTimeOffset(0)._2 should be < 50.millis
    // second batch is emitted immediately after 100ms timeout after 200ms sleep
    elementsWithEmittedTimeOffset(1)._2 should (be >= 200.millis and be < 250.millis)
  }

  it should "send the group only once when the channel is closed" in supervised {
    val c = StageCapacity.newChannel[Int]
    fork {
      c.send(1)
      c.send(2)
      c.done()
    }
    Try(timeout(2.seconds)(c.groupedWithin(3, 5.minutes).toList)) shouldBe Success(List(List(1, 2)))
  }

  it should "return failed source when the original source is failed" in supervised {
    val failure = new RuntimeException()
    Source.failed(failure).groupedWithin(3, 10.seconds).receiveOrClosed() shouldBe ChannelClosed.Error(failure)
  }

  behavior of "SourceOps.groupedWeightedWithin"

  it should "group elements on timeout in the first batch and consider max weight in the remaining batches" in supervised {
    val c = StageCapacity.newChannel[Int]
    fork {
      c.send(1)
      c.send(2)
      sleep(150.millis)
      c.send(3)
      c.send(4)
      c.send(5)
      c.send(6)
      c.done()
    }
    c.groupedWeightedWithin(10, 100.millis)(n => n * 2).toList shouldBe List(List(1, 2), List(3, 4), List(5), List(6))
  }

  it should "return failed source when cost function throws exception" in supervised {
    val source = Source.fromValues(1, 2, 3, 0, 4, 5, 6, 7).groupedWeightedWithin(150, 100.millis)(n => 100 / n)
    source.receiveOrClosed() shouldBe Seq(1, 2)
    source.receiveOrClosed() should matchPattern {
      case ChannelClosed.Error(reason) if reason.isInstanceOf[ArithmeticException] =>
    }
  }

  it should "return failed source when the original source is failed" in supervised {
    val failure = new RuntimeException()
    Source.failed[Long](failure).groupedWeightedWithin(10, 100.millis)(n => n * 2).receiveOrClosed() shouldBe ChannelClosed.Error(failure)
  }
}
