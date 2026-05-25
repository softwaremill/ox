package ox.flow

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.*

class FlowOpsLastTest extends AnyFlatSpec with Matchers:
  behavior of "last"

  it should "throw NoSuchElementException for the empty source" in:
    the[NoSuchElementException] thrownBy {
      Flow.empty[Int].runLast()
    } should have message "cannot obtain last element from an empty source"

  it should "throw ChannelClosedException.Error with exception and message that was thrown during retrieval" in:
    the[RuntimeException] thrownBy {
      Flow
        .failed(new RuntimeException("source is broken"))
        .runLast()
    } should have message "source is broken"

  it should "return last element for the non-empty source" in:
    Flow.fromValues(1, 2).runLast() shouldBe 2

end FlowOpsLastTest
