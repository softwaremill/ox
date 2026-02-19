package ox.flow

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.*
import ox.channels.ChannelClosedException

class FlowOpsOnErrorRecoverTest extends AnyFlatSpec with Matchers:

  behavior of "Flow.onErrorRecover"

  it should "pass through elements when upstream flow succeeds" in:
    val flow = Flow.fromValues(1, 2, 3)
    val result = flow.onErrorRecover(_ => 99).runToList()
    result shouldBe List(1, 2, 3)

  it should "emit recovery value on any error" in:
    val flow = Flow.fromValues(1, 2).concat(Flow.failed(new RuntimeException("boom")))
    val result = flow.onErrorRecover(_ => 42).runToList()
    result shouldBe List(1, 2, 42)

  it should "use exception to produce recovery value" in:
    val flow = Flow.fromValues(1).concat(Flow.failed(new RuntimeException("msg")))
    val result = flow.onErrorRecover(e => e.getMessage.length).runToList()
    result shouldBe List(1, 3)

  it should "work with empty flow" in:
    val flow = Flow.empty[Int]
    val result = flow.onErrorRecover(_ => 99).runToList()
    result shouldBe List.empty

  it should "propagate exception thrown by recovery function" in:
    val flow = Flow.fromValues(1).concat(Flow.failed(new RuntimeException("original")))
    val caught = the[ChannelClosedException.Error] thrownBy {
      flow.onErrorRecover(_ => throw new IllegalStateException("recovery failed")).runToList()
    }
    caught.getCause shouldBe an[IllegalStateException]

end FlowOpsOnErrorRecoverTest
