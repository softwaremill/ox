package ox.flow

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.*

class FlowOpsConcatPrependTest extends AnyFlatSpec with Matchers:

  behavior of "concat"

  it should "concat other source" in:
    Flow.fromValues(1, 2, 3).concat(Flow.fromValues(4, 5, 6)).runToList() shouldBe List(1, 2, 3, 4, 5, 6)

  behavior of "prepend"

  it should "prepend other source" in:
    Flow.fromValues(1, 2, 3).prepend(Flow.fromValues(4, 5, 6)).runToList() shouldBe List(4, 5, 6, 1, 2, 3)
end FlowOpsConcatPrependTest
