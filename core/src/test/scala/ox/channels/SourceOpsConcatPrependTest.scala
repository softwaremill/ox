package ox.channels

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.*

class SourceOpsConcatPrependTest extends AnyFlatSpec with Matchers {

  behavior of "Source.concat"

  it should "concat other source" in supervised {
    Source.fromValues(1, 2, 3).concat(Source.fromValues(4, 5, 6)).toList shouldBe List(1, 2, 3, 4, 5, 6)
  }

  behavior of "Source.prepend"

  it should "prepend other source" in supervised {
    Source.fromValues(1, 2, 3).prepend(Source.fromValues(4, 5, 6)).toList shouldBe List(4, 5, 6, 1, 2, 3)
  }
}
