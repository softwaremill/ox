package ox.channels

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.*

class SourceOpsTakeWhileTest extends AnyFlatSpec with Matchers {
  behavior of "Source.takeWhile"

  it should "not take from the empty source" in supervised {
    val s = Source.empty[Int]
    s.takeWhile(_ < 3).toList shouldBe List.empty
  }

  it should "take as long as predicate is satisfied" in supervised {
    val s = Source.fromValues(1, 2, 3)
    s.takeWhile(_ < 3).toList shouldBe List(1, 2)
  }

  it should "not take if predicate fails for first or more elements" in supervised {
    val s = Source.fromValues(3, 2, 1)
    s.takeWhile(_ < 3).toList shouldBe List()
  }
}
