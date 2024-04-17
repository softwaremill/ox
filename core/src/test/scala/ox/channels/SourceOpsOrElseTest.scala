package ox.channels

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.*

class SourceOpsOrElseTest extends AnyFlatSpec with Matchers {
  behavior of "SourceOps.orElse"

  it should "emit elements only from the original source when it is not empty" in supervised {
    Source.fromValues(1).orElse(Source.fromValues(2, 3)).toList shouldBe List(1)
  }

  it should "emit elements only from the alternative source when the original source is created empty" in supervised {
    Source.empty.orElse(Source.fromValues(2, 3)).toList shouldBe List(2, 3)
  }

  it should "emit elements only from the alternative source when the original source is empty" in supervised {
    Source.fromValues[Int]().orElse(Source.fromValues(2, 3)).toList shouldBe List(2, 3)
  }

  it should "return failed source when the original source is failed" in supervised {
    val failure = new RuntimeException()
    Source.failed(failure).orElse(Source.fromValues(2, 3)).receiveOrClosed() shouldBe ChannelClosed.Error(failure)
  }
}
