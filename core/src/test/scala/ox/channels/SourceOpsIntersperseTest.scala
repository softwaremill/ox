package ox.channels

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.*

class SourceOpsIntersperseTest extends AnyFlatSpec with Matchers {
  behavior of "Source.intersperse"

  it should "intersperse with inject only over an empty source" in supervised {
    val s = Source.empty[String]
    s.intersperse(", ").toList shouldBe List.empty
  }

  it should "intersperse with inject only over a source with one element" in supervised {
    val s = Source.fromValues("foo")
    s.intersperse(", ").toList shouldBe List("foo")
  }

  it should "intersperse with inject only over a source with multiple elements" in supervised {
    val s = Source.fromValues("foo", "bar")
    s.intersperse(", ").toList shouldBe List("foo", ", ", "bar")
  }

  it should "intersperse with start, inject and end over an empty source" in supervised {
    val s = Source.empty[String]
    s.intersperse("[", ", ", "]").toList shouldBe List("[", "]")
  }

  it should "intersperse with start, inject and end over a source with one element" in supervised {
    val s = Source.fromValues("foo")
    s.intersperse("[", ", ", "]").toList shouldBe List("[", "foo", "]")
  }

  it should "intersperse with start, inject and end over a source with multiple elements" in supervised {
    val s = Source.fromValues("foo", "bar")
    s.intersperse("[", ", ", "]").toList shouldBe List("[", "foo", ", ", "bar", "]")
  }
}
