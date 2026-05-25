package ox.flow

import org.scalatest.OptionValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.*

class FlowOpsLastOptionTest extends AnyFlatSpec with Matchers with OptionValues:

  behavior of "lastOption"

  it should "return None for the empty flow" in:
    Flow.empty[Int].runLastOption() shouldBe None

  it should "return Some for a non-empty" in:
    val s = Flow.fromValues(1, 2, 3)
    s.runLastOption() shouldBe Some(3)

  it should "throw ChannelClosedException.Error with exception and message that was thrown during retrieval" in:
    the[RuntimeException] thrownBy {
      Flow
        .failed(new RuntimeException("source is broken"))
        .runLastOption()
    } should have message "source is broken"

end FlowOpsLastOptionTest
