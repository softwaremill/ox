package ox.channels

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.*

class SourceOpsZipAllTest extends AnyFlatSpec with Matchers {
  behavior of "Source.zipAll"

  it should "not emit any element when both channels are empty" in scoped {
    val s = Source.empty[Int]
    val other = Source.empty[String]

    s.zipAll(other, -1, "foo").toList shouldBe List.empty
  }

  it should "emit this element when other channel is empty" in scoped {
    val s = Source.fromValues(1)
    val other = Source.empty[String]

    s.zipAll(other, -1, "foo").toList shouldBe List((1, "foo"))
  }

  it should "emit other element when this channel is empty" in scoped {
    val s = Source.empty[Int]
    val other = Source.fromValues("a")

    s.zipAll(other, -1, "foo").toList shouldBe List((-1, "a"))
  }

  it should "emit matching elements when both channels are of the same size" in scoped {
    val s = Source.fromValues(1, 2)
    val other = Source.fromValues("a", "b")

    s.zipAll(other, -1, "foo").toList shouldBe List((1, "a"), (2, "b"))
  }

  it should "emit default for other channel if this channel is longer" in scoped {
    val s = Source.fromValues(1, 2, 3)
    val other = Source.fromValues("a")

    s.zipAll(other, -1, "foo").toList shouldBe List((1, "a"), (2, "foo"), (3, "foo"))
  }

  it should "emit default for this channel if other channel is longer" in scoped {
    val s = Source.fromValues(1)
    val other = Source.fromValues("a", "b", "c")

    s.zipAll(other, -1, "foo").toList shouldBe List((1, "a"), (-1, "b"), (-1, "c"))
  }
}
