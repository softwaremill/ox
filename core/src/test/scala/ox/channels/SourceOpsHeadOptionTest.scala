package ox.channels

import org.scalatest.OptionValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.*

class SourceOpsHeadOptionTest extends AnyFlatSpec with Matchers with OptionValues {
  behavior of "Source.headOption"

  it should "return None for the empty source" in supervised {
    Source.empty[Int].headOption() shouldBe None
  }

  it should "return None for the failed source" in supervised {
    Source
      .failed(new RuntimeException("source is broken"))
      .headOption() shouldBe None
  }

  it should "return Some element for the non-empty source" in supervised {
    Source.fromValues(1, 2).headOption().value shouldBe 1
  }

  it should "be not idempotent operation" in supervised {
    val s = Source.fromValues(1, 2)
    s.headOption().value shouldBe 1
    s.headOption().value shouldBe 2
  }
}
