package ox.flow

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class FlowOpsTakeLastTest extends AnyFlatSpec with Matchers:
  behavior of "takeLast"

  it should "throw ChannelClosedException.Error for source failed without exception" in:
    the[IllegalStateException] thrownBy {
      Flow
        .failed[Int](new IllegalStateException())
        .runTakeLast(1)
    }

  it should "fail to takeLast when n < 0" in:
    the[IllegalArgumentException] thrownBy {
      Flow.empty[Int].runTakeLast(-1)
    } should have message "requirement failed: n must be >= 0"

  it should "return empty list for the empty source" in:
    Flow.empty[Int].runTakeLast(1) shouldBe List.empty

  it should "return empty list when n == 0 and list is not empty" in:
    Flow.fromValues(1).runTakeLast(0) shouldBe List.empty

  it should "return list with all elements if the source is smaller than requested number" in:
    Flow.fromValues(1, 2).runTakeLast(3) shouldBe List(1, 2)

  it should "return the last n elements from the source" in:
    Flow.fromValues(1, 2, 3, 4, 5).runTakeLast(2) shouldBe List(4, 5)
end FlowOpsTakeLastTest
