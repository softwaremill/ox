package ox.channels

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.*

class SourceOpsDropWhileTest extends AnyFlatSpec with Matchers {
  behavior of "Source.dropWhile"

  it should "not drop from the empty source" in supervised {
    val s = Source.empty[Int]
    s.dropWhile(_ > 0).toList shouldBe List.empty
  }

  it should "drop elements from the source while predicate is true" in supervised {
    val s = Source.fromValues(1, 2, 3)
    s.dropWhile(_ < 3).toList shouldBe List(3)
  }

  it should "drop elements from the source until predicate is true and then emit subsequent ones" in supervised {
    val s = Source.fromValues(1, 2, 3, 2)
    s.dropWhile(_ < 3).toList shouldBe List(3, 2)
  }

  it should "not drop elements from the source if predicate is false" in supervised {
    val s = Source.fromValues(1, 2, 3)
    s.dropWhile(_ > 3).toList shouldBe List(1, 2, 3)
  }

  it should "not drop elements from the source when predicate is false for first or more elements" in supervised {
    val s = Source.fromValues(1, 4, 5)
    s.dropWhile(_ > 3).toList shouldBe List(1, 4, 5)
  }
}
