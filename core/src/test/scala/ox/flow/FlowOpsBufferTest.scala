package ox.flow

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.channels.ChannelClosedException

class FlowOpsBufferTest extends AnyFlatSpec with Matchers:

  behavior of "buffer"

  it should "work with a single async boundary" in:
    Flow.fromValues(1, 2, 3).buffer().runToList() shouldBe List(1, 2, 3)

  it should "work with multiple async boundaries" in:
    Flow.fromValues(1, 2, 3).buffer().map(_ + 1).buffer().map(_ * 10).buffer().runToList() shouldBe List(20, 30, 40)

  it should "propagate errors" in:
    intercept[ChannelClosedException.Error] {
      Flow.fromValues(1, 2, 3).map(_ => throw new IllegalStateException).buffer().runToList()
    }.getCause() shouldBe a[IllegalStateException]
end FlowOpsBufferTest
