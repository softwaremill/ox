package ox.flow

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.*
import ox.channels.ChannelClosed

class FlowOpsFailedTest extends AnyFlatSpec with Matchers:

  behavior of "failed"

  it should "fail on receive" in supervised:
    // when
    val s = Flow.failed(RuntimeException("boom"))

    // then
    s.runToChannel().receiveOrClosed() should matchPattern:
      case ChannelClosed.Error(reason) if reason.getMessage == "boom" =>

end FlowOpsFailedTest
