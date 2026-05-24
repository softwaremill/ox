package ox.channels

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.*

class SourceOpsEmptyTest extends AnyFlatSpec with Matchers:

  behavior of "Source.empty"

  it should "be done" in supervised {
    Source.empty.isClosedForReceive shouldBe true
  }

  it should "be empty" in supervised {
    Source.empty.toList shouldBe empty
  }
end SourceOpsEmptyTest
