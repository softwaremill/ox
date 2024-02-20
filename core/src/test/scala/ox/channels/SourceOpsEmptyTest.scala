package ox.channels

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.*

class SourceOpsEmptyTest extends AnyFlatSpec with Matchers {

  behavior of "Source.empty"

  it should "be done" in scoped {
    Source.empty.isClosedForReceive shouldBe true
  }

  it should "be empty" in scoped {
    Source.empty.toList shouldBe empty
  }
}
