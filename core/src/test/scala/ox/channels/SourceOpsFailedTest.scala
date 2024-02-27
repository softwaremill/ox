package ox.channels

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.*

class SourceOpsFailedTest extends AnyFlatSpec with Matchers {

  behavior of "Source.failed"

  it should "fail on receive" in supervised {
    // when
    val s = Source.failed(RuntimeException("boom"))

    // then
    s.receiveSafe() should matchPattern { case ChannelClosed.Error(reason) if reason.getMessage == "boom" => }
  }

  it should "be in error" in supervised {
    Source.failed(RuntimeException("boom")).isClosedForReceive shouldBe true
  }
}
