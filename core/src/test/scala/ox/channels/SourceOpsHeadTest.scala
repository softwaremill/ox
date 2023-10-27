package ox.channels

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.*

class SourceOpsHeadTest extends AnyFlatSpec with Matchers {
  behavior of "Source.head"

  it should "throw NoSuchElementException for the empty source" in supervised {
    the[NoSuchElementException] thrownBy {
      Source.empty[Int].head()
    } should have message "cannot obtain head from an empty source"
  }

  it should "throw ChannelClosedException.Error with exception and message that was thrown during retrieval" in supervised {
    the[ChannelClosedException.Error] thrownBy {
      Source
        .failed(new RuntimeException("source is broken"))
        .head()
    } should have message "java.lang.RuntimeException: source is broken"
  }

  it should "throw ChannelClosedException.Error for source failed without exception" in supervised {
    the[ChannelClosedException.Error] thrownBy {
      Source.failedWithoutReason[Int]().head()
    }
  }

  it should "return first value from non empty source" in supervised {
    Source.fromValues(1, 2).head() shouldBe 1
  }

  it should "be not idempotent operation" in supervised {
    val s = Source.fromValues(1, 2)
    s.head() shouldBe 1
    s.head() shouldBe 2
  }
}
