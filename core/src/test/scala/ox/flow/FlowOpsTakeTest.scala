package ox.flow

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.*

class FlowOpsTakeTest extends AnyFlatSpec with Matchers:
  behavior of "take"

  it should "take from a simple flow" in:
    val f = Flow.fromValues(1 to 10*)

    f.take(5).runToList() shouldBe (1 to 5)

  it should "take from an async flow" in:
    val f = Flow.fromValues(1 to 10*).async()

    f.take(5).runToList() shouldBe (1 to 5)

  it should "take all if the flow ends sooner than the desired number of elements" in:
    val f = Flow.fromValues(1 to 10*)

    f.take(50).runToList() shouldBe (1 to 10)
end FlowOpsTakeTest
