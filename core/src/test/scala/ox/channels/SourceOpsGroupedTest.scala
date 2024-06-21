package ox.channels

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
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
}
