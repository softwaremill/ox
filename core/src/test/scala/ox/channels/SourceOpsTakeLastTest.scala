package ox.channels

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.*

class SourceOpsTakeLastTest extends AnyFlatSpec with Matchers {
  behavior of "SourceOps.takeLast"

  it should "throw ChannelClosedException.Error with exception and message that was thrown during retrieval" in supervised {
    the[ChannelClosedException.Error] thrownBy {
      Source
        .failed[Int](new RuntimeException("source is broken"))
        .takeLast(1)
    } should have message "java.lang.RuntimeException: source is broken"
  }

  it should "throw ChannelClosedException.Error for source failed without exception" in supervised {
    the[ChannelClosedException.Error] thrownBy {
      Source
        .failed[Int](new RuntimeException())
        .takeLast(1)
    }
  }

  it should "fail to takeLast when n < 0" in supervised {
    the[IllegalArgumentException] thrownBy {
      Source.empty[Int].takeLast(-1)
    } should have message "requirement failed: n must be >= 0"
  }

  it should "return empty list for the empty source" in supervised {
    Source.empty[Int].takeLast(1) shouldBe List.empty
  }

  it should "return empty list when n == 0 and list is not empty" in supervised {
    Source.fromValues(1).takeLast(0) shouldBe List.empty
  }

  it should "return list with all elements if the source is smaller than requested number" in supervised {
    Source.fromValues(1, 2).takeLast(3) shouldBe List(1, 2)
  }

  it should "return the last n elements from the source" in supervised {
    Source.fromValues(1, 2, 3, 4, 5).takeLast(2) shouldBe List(4, 5)
  }

  it should "drain the source" in supervised {
    val s = Source.fromValues(1)
    s.takeLast(1) shouldBe List(1)
    s.receive() shouldBe ChannelClosed.Done
  }
}
