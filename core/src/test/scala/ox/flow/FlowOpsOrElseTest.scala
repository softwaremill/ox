package ox.flow

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.*
import ox.channels.ChannelClosed

class FlowOpsOrElseTest extends AnyFlatSpec with Matchers:
  behavior of "orElse"

  it should "emit elements only from the original source when it is not empty" in:
    Flow.fromValues(1).orElse(Flow.fromValues(2, 3)).runToList() shouldBe List(1)

  it should "emit elements only from the alternative source when the original source is created empty" in:
    Flow.empty.orElse(Flow.fromValues(2, 3)).runToList() shouldBe List(2, 3)

  it should "emit elements only from the alternative source when the original source is empty" in:
    Flow.fromValues[Int]().orElse(Flow.fromValues(2, 3)).runToList() shouldBe List(2, 3)

  it should "return failed source when the original source is failed" in supervised:
    val failure = new RuntimeException()
    Flow.failed(failure).orElse(Flow.fromValues(2, 3)).runToChannel().receiveOrClosed() shouldBe ChannelClosed.Error(failure)
end FlowOpsOrElseTest
