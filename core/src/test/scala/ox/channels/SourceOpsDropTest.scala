package ox.channels

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.*

class SourceOpsDropTest extends AnyFlatSpec with Matchers {
  behavior of "Source.drop"

  it should "not drop from the empty source" in supervised {
    val s = Source.empty[Int]
    s.drop(1).toList shouldBe List.empty
  }

  it should "drop elements from the source" in supervised {
    val s = Source.fromValues(1, 2, 3)
    s.drop(2).toList shouldBe List(3)
  }

  it should "return empty source when more elements than source length was dropped" in supervised {
    val s = Source.fromValues(1, 2)
    s.drop(3).toList shouldBe List.empty
  }

  it should "not drop when 'n == 0'" in supervised {
    val s = Source.fromValues(1, 2, 3)
    s.drop(0).toList shouldBe List(1, 2, 3)
  }
}
