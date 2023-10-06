package ox.channels

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.*

class SourceOpsStatefulMapTest extends AnyFlatSpec with Matchers {

  behavior of "Source.statefulMap"

  it should "zip with index" in scoped {
    val c = Source.fromValues("a", "b", "c")

    val s = c.statefulMap(() => 0)((index, element) => (index + 1, Some((element, index))))

    s.toList shouldBe List(("a", 0), ("b", 1), ("c", 2))
  }

  it should "calculate a running total" in scoped {
    val c = Source.fromValues(1, 2, 3, 4, 5)

    val s = c.statefulMap(() => 0)((sum, element) => (sum + element, Some(sum)), Some.apply)

    s.toList shouldBe List(0, 1, 3, 6, 10, 15)
  }

  it should "deduplicate" in scoped {
    val c = Source.fromValues(1, 2, 2, 3, 2, 4, 3, 1, 5)

    val s = c.statefulMap(() => Set.empty[Int])((alreadySeen, element) =>
      val result = Option.unless(alreadySeen.contains(element))(element)
      (alreadySeen + element, result)
    )

    s.toList shouldBe List(1, 2, 3, 4, 5)
  }
}
