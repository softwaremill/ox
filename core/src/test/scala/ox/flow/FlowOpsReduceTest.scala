package ox.flow

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.*

class FlowOpsReduceTest extends AnyFlatSpec with Matchers:
  behavior of "reduce"

  it should "throw NoSuchElementException for reduce over the empty source" in:
    the[NoSuchElementException] thrownBy {
      Flow.empty[Int].runReduce(_ + _)
    } should have message "cannot reduce an empty flow"

  it should "throw exception thrown in `f` when `f` throws" in:
    the[RuntimeException] thrownBy {
      Flow
        .fromValues(1, 2)
        .runReduce((_, _) => throw new RuntimeException("Function `f` is broken"))
    } should have message "Function `f` is broken"

  it should "return first element from reduce over the single element source" in:
    Flow.fromValues(1).runReduce(_ + _) shouldBe 1

  it should "run reduce over on non-empty source" in:
    Flow.fromValues(1, 2).runReduce(_ + _) shouldBe 3
end FlowOpsReduceTest
