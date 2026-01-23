package ox.flow

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.*

class FlowOpsUsingChannelTest extends AnyFlatSpec with Matchers:
  behavior of "usingChannel"

  it should "send elements through the provided channel" in supervised:
    Flow
      .usingChannel(sink =>
        sink.send(1)
        sink.send(2)
        sink.send(3)
      )
      .runToList() shouldBe List(1, 2, 3)

  it should "complete when the channel is done" in supervised:
    Flow
      .usingChannel[Int](sink =>
        sink.send(10)
        sink.send(20)
      )
      .runToList() shouldBe List(10, 20)

  it should "propagate errors from the channel" in supervised:
    val exception = new RuntimeException("test error")
    val result = intercept[RuntimeException]:
      Flow
        .usingChannel[Int](sink =>
          sink.send(1)
          throw exception
        )
        .runToList()
    result shouldBe exception

  it should "work with transformations" in supervised:
    Flow
      .usingChannel[Int](sink =>
        sink.send(1)
        sink.send(2)
        sink.send(3)
      )
      .map(_ * 2)
      .runToList() shouldBe List(2, 4, 6)

  it should "support concurrent sending" in supervised:
    Flow
      .usingChannel[Int](sink =>
        fork:
          sink.send(1)
          sink.send(2)
        fork:
          sink.send(3)
          sink.send(4)
      )
      .runToList()
      .sorted shouldBe List(1, 2, 3, 4)
end FlowOpsUsingChannelTest
