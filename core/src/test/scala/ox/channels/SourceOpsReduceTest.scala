package ox.channels

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.*

class SourceOpsReduceTest extends AnyFlatSpec with Matchers {
  behavior of "SourceOps.reduce"

  it should "throw NoSuchElementException for reduce over the empty source" in supervised {
    the[NoSuchElementException] thrownBy {
      Source.empty[Int].reduce(_ + _)
    } should have message "cannot reduce an empty source"
  }

  it should "throw ChannelClosedException.Error with exception and message that was thrown during retrieval" in supervised {
    the[ChannelClosedException.Error] thrownBy {
      Source
        .failed[Int](new RuntimeException("source is broken"))
        .reduce(_ + _)
    } should have message "java.lang.RuntimeException: source is broken"
  }

  it should "throw ChannelClosedException.Error for source failed without exception" in supervised {
    the[ChannelClosedException.Error] thrownBy {
      Source
        .failedWithoutReason[Int]()
        .reduce(_ + _)
    }
  }

  it should "throw exception thrown in `f` when `f` throws" in supervised {
    the[RuntimeException] thrownBy {
      Source
        .fromValues(1, 2)
        .reduce((_, _) => throw new RuntimeException("Function `f` is broken"))
    } should have message "Function `f` is broken"
  }

  it should "return first element from reduce over the single element source" in supervised {
    Source.fromValues(1).reduce(_ + _) shouldBe 1
  }

  it should "run reduce over on non-empty source" in supervised {
    Source.fromValues(1, 2).reduce(_ + _) shouldBe 3
  }

  it should "drain the source" in supervised {
    val s = Source.fromValues(1)
    s.reduce(_ + _) shouldBe 1
    s.receive() shouldBe ChannelClosed.Done
  }
}
