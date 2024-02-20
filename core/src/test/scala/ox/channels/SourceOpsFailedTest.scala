package ox.channels

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.*

class SourceOpsFailedTest extends AnyFlatSpec with Matchers {

  behavior of "Source.failed"

  it should "fail on receive" in scoped {
    // when
    val s = Source.failed(RuntimeException("boom"))

    // then  
    s.receive() should matchPattern { case ChannelClosed.Error(reason) if reason.getMessage == "boom" => }
  }

  it should "be in error" in scoped {
    Source.failed(RuntimeException("boom")).isClosedForReceive shouldBe true
  }
}
