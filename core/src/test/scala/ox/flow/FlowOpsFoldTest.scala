package ox.flow

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.*

class FlowOpsFoldTest extends AnyFlatSpec with Matchers:
  behavior of "fold"

  it should "throw an exception for a failed flow" in:
    the[IllegalStateException] thrownBy:
      Flow
        .failed[Int](new IllegalStateException())
        .runFold(0)((acc, n) => acc + n)

  it should "throw exception thrown in `f` when `f` throws" in:
    the[RuntimeException] thrownBy {
      Flow
        .fromValues(1)
        .runFold(0)((_, _) => throw new RuntimeException("Function `f` is broken"))
    } should have message "Function `f` is broken"

  it should "return `zero` value from fold on the empty source" in:
    Flow.empty[Int].runFold(0)((acc, n) => acc + n) shouldBe 0

  it should "return fold on non-empty fold" in:
    Flow.fromValues(1, 2).runFold(0)((acc, n) => acc + n) shouldBe 3
end FlowOpsFoldTest
