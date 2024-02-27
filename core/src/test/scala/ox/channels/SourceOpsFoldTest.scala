package ox.channels

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.*

class SourceOpsFoldTest extends AnyFlatSpec with Matchers {
  behavior of "Source.fold"

  it should "throw ChannelClosedException.Error with exception and message that was thrown during retrieval" in supervised {
    the[ChannelClosedException.Error] thrownBy {
      Source
        .failed[Int](new RuntimeException("source is broken"))
        .fold(0)((acc, n) => acc + n)
    } should have message "java.lang.RuntimeException: source is broken"
  }

  it should "throw ChannelClosedException.Error for source failed without exception" in supervised {
    the[ChannelClosedException.Error] thrownBy {
      Source
        .failed[Int](new RuntimeException())
        .fold(0)((acc, n) => acc + n)
    }
  }

  it should "throw exception thrown in `f` when `f` throws" in supervised {
    the[RuntimeException] thrownBy {
      Source
        .fromValues(1)
        .fold(0)((_, _) => throw new RuntimeException("Function `f` is broken"))
    } should have message "Function `f` is broken"
  }

  it should "return `zero` value from fold on the empty source" in supervised {
    Source.empty[Int].fold(0)((acc, n) => acc + n) shouldBe 0
  }

  it should "return fold on non-empty source" in supervised {
    Source.fromValues(1, 2).fold(0)((acc, n) => acc + n) shouldBe 3
  }

  it should "drain the source" in supervised {
    val s = Source.fromValues(1)
    s.fold(0)((acc, n) => acc + n) shouldBe 1
    s.receiveSafe() shouldBe ChannelClosed.Done
  }
}
