package ox.channels

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.*

class SourceOpsLastTest extends AnyFlatSpec with Matchers {
  behavior of "SourceOps.last"

  it should "throw NoSuchElementException for the empty source" in supervised {
    the[NoSuchElementException] thrownBy {
      Source.empty[Int].last()
    } should have message "cannot obtain last element from an empty source"
  }

  it should "throw ChannelClosedException.Error with exception and message that was thrown during retrieval" in supervised {
    the[ChannelClosedException.Error] thrownBy {
      Source
        .failed(new RuntimeException("source is broken"))
        .last()
    } should have message "java.lang.RuntimeException: source is broken"
  }

  it should "throw ChannelClosedException.Error for source failed without exception" in supervised {
    the[ChannelClosedException.Error] thrownBy {
      Source.failedWithoutReason[Int]().last()
    }
  }

  it should "return last element for the non-empty source" in supervised {
    Source.fromValues(1, 2).last() shouldBe 2
  }

  it should "drain the source" in supervised {
    val s = Source.fromValues(1)
    s.last() shouldBe 1
    s.receive() shouldBe ChannelClosed.Done
  }
}
