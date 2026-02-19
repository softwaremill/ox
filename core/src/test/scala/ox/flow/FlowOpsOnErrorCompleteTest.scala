package ox.flow

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers


class FlowOpsOnErrorCompleteTest extends AnyFlatSpec with Matchers:

  behavior of "Flow.onErrorComplete"

  it should "pass through elements when upstream flow succeeds" in:
    val flow = Flow.fromValues(1, 2, 3)
    val result = flow.onErrorComplete.runToList()
    result shouldBe List(1, 2, 3)

  it should "complete normally on any error" in:
    val flow = Flow.fromValues(1, 2).concat(Flow.failed(new RuntimeException("boom")))
    val result = flow.onErrorComplete.runToList()
    result shouldBe List(1, 2)

  it should "complete normally on error during processing" in:
    val flow = Flow.fromValues(1, 2, 3).map(x => if x == 3 then throw new RuntimeException("fail") else x)
    val result = flow.onErrorComplete.runToList()
    result shouldBe List(1, 2)

  it should "work with empty flow" in:
    val flow = Flow.empty[Int]
    val result = flow.onErrorComplete.runToList()
    result shouldBe List.empty

  it should "work with flow that immediately fails" in:
    val flow = Flow.failed[Int](new RuntimeException("immediate"))
    val result = flow.onErrorComplete.runToList()
    result shouldBe List.empty

  behavior of "Flow.onErrorComplete with partial function"

  it should "complete normally when error matches partial function" in:
    val flow = Flow.fromValues(1, 2).concat(Flow.failed(new IllegalArgumentException("boom")))
    val result = flow.onErrorComplete { case _: IllegalArgumentException => true }.runToList()
    result shouldBe List(1, 2)

  it should "propagate error when partial function is not defined" in:
    val flow = Flow.fromValues(1, 2).concat(Flow.failed(new RuntimeException("unhandled")))
    the[RuntimeException] thrownBy {
      flow.onErrorComplete { case _: IllegalArgumentException => true }.runToList()
    } should have message "unhandled"

  it should "propagate error when partial function returns false" in:
    val flow = Flow.fromValues(1, 2).concat(Flow.failed(new RuntimeException("rejected")))
    the[RuntimeException] thrownBy {
      flow.onErrorComplete { case _: RuntimeException => false }.runToList()
    } should have message "rejected"

end FlowOpsOnErrorCompleteTest
