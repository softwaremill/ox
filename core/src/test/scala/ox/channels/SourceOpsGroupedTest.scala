package ox.channels

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.duration.DurationInt
import ox.*

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

  it should "return failed source when the original source is failed" in supervised {
    val failure = new RuntimeException()
    Source.failed[Long](failure).groupedWeighted(10)(n => n * 2).receiveOrClosed() shouldBe ChannelClosed.Error(failure)
  }

  behavior of "SourceOps.groupedWithin"

  it should "group elements on timeout in the first batch" in supervised {
    val c = StageCapacity.newChannel[Int]
    fork {
      c.send(1)
      c.send(2)
      sleep(200.millis)
      c.send(3)
      c.send(4)
      c.send(5)
      c.send(6)
      c.done()
    }
    c.groupedWithin(3, 100.millis).toList shouldBe List(List(1, 2), List(3, 4, 5), List(6))
  }

  it should "group elements on timeout in the second batch" in supervised {
    val c = StageCapacity.newChannel[Int]
    fork {
      c.send(1)
      c.send(2)
      c.send(3)
      c.send(4)
      sleep(200.millis)
      c.send(5)
      c.send(6)
      c.send(7)
      c.done()
    }
    c.groupedWithin(3, 100.millis).toList shouldBe List(List(1, 2, 3), List(4), List(5, 6, 7))
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
      sleep(200.millis)
      c.send(3)
      c.send(4)
      c.send(5)
      c.send(6)
      c.done()
    }
    c.groupedWeightedWithin(10, 100.millis)(n => n * 2).toList shouldBe List(List(1, 2), List(3, 4), List(5), List(6))
  }

  it should "return failed source when the original source is failed" in supervised {
    val failure = new RuntimeException()
    Source.failed[Long](failure).groupedWeightedWithin(10, 100.millis)(n => n * 2).receiveOrClosed() shouldBe ChannelClosed.Error(failure)
  }
}
