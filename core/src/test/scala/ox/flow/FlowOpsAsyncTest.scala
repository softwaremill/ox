package ox.flow

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.channels.ChannelClosedException

class FlowOpsAsyncTest extends AnyFlatSpec with Matchers:

  behavior of "async"

  it should "work with a single async boundary" in:
    Flow.fromValues(1, 2, 3).async().runToList() shouldBe List(1, 2, 3)

  it should "work with multiple async boundaries" in:
    Flow.fromValues(1, 2, 3).async().map(_ + 1).async().map(_ * 10).async().runToList() shouldBe List(20, 30, 40)

  it should "propagate errors" in:
    intercept[ChannelClosedException.Error] {
      Flow.fromValues(1, 2, 3).map(_ => throw new IllegalStateException).async().runToList()
    }.getCause() shouldBe a[IllegalStateException]
end FlowOpsAsyncTest
