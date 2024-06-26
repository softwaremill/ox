package ox.channels

import org.scalatest.concurrent.Eventually
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.*

class SourceOpsSlidingTest extends AnyFlatSpec with Matchers with Eventually {

  behavior of "SourceOps.sliding"

  it should "create sliding windows for n = 2 and step = 1" in {
    supervised {
      Source.fromValues(1, 2, 3, 4).sliding(2).toList shouldBe List(List(1, 2), List(2, 3), List(3, 4))
    }
  }

  it should "create sliding windows for n = 3 and step = 1" in {
    supervised {
      Source.fromValues(1, 2, 3, 4, 5).sliding(3).toList shouldBe List(List(1, 2, 3), List(2, 3, 4), List(3, 4, 5))
    }
  }

  it should "create sliding windows for n = 2 and step = 2" in {
    supervised {
      Source.fromValues(1, 2, 3, 4, 5).sliding(2, step = 2).toList shouldBe List(List(1, 2), List(3, 4), List(5))
    }
  }

  it should "create sliding windows for n = 3 and step = 2" in {
    supervised {
      Source.fromValues(1, 2, 3, 4, 5, 6).sliding(3, step = 2).toList shouldBe List(List(1, 2, 3), List(3, 4, 5), List(5, 6))
    }
  }

  it should "create sliding windows for n = 1 and step = 2" in {
    supervised {
      Source.fromValues(1, 2, 3, 4, 5).sliding(1, step = 2).toList shouldBe List(List(1), List(3), List(5))
    }
  }

  it should "create sliding windows for n = 2 and step = 3" in {
    supervised {
      Source.fromValues(1, 2, 3, 4, 5, 6).sliding(2, step = 3).toList shouldBe List(List(1, 2), List(4, 5))
    }
  }

  it should "create sliding windows for n = 2 and step = 3 (with 1 element remaining in the end)" in {
    supervised {
      Source.fromValues(1, 2, 3, 4, 5, 6, 7).sliding(2, step = 3).toList shouldBe List(List(1, 2), List(4, 5), List(7))
    }
  }

  it should "return failed source when the original source is failed" in supervised {
    val failure = new RuntimeException()
    Source.failed[Long](failure).sliding(1, 2).receiveOrClosed() shouldBe ChannelClosed.Error(failure)
  }
}
