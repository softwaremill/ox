package ox.channels

import org.scalatest.OptionValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.*

class SourceOpsLastOptionTest extends AnyFlatSpec with Matchers with OptionValues {
  behavior of "SourceOps.lastOption"

  it should "return None for the empty source" in supervised {
    Source.empty[Int].lastOption() shouldBe None
  }

  it should "throw ChannelClosedException.Error with exception and message that was thrown during retrieval" in supervised {
    the[ChannelClosedException.Error] thrownBy {
      Source
        .failed(new RuntimeException("source is broken"))
        .lastOption()
    } should have message "java.lang.RuntimeException: source is broken"
  }

  it should "throw ChannelClosedException.Error for source failed without exception" in supervised {
    the[ChannelClosedException.Error] thrownBy {
      Source.failed[Int](new RuntimeException()).lastOption()
    }
  }

  it should "return last element wrapped in Some for the non-empty source" in supervised {
    Source.fromValues(1, 2).lastOption().value shouldBe 2
  }

  it should "drain the source" in supervised {
    val s = Source.fromValues(1)
    s.lastOption().value shouldBe 1
    s.receiveSafe() shouldBe ChannelClosed.Done
  }
}
