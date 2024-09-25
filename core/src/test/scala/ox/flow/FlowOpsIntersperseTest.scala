package ox.flow

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.*

class FlowOpsIntersperseTest extends AnyFlatSpec with Matchers:
  behavior of "Flow.intersperse"

  it should "intersperse with inject only over an empty source" in supervised {
    val f = Flow.empty[String]
    f.intersperse(", ").runToList shouldBe List.empty
  }

  it should "intersperse with inject only over a source with one element" in supervised {
    val f = Flow.fromValues("foo")
    f.intersperse(", ").runToList shouldBe List("foo")
  }

  it should "intersperse with inject only over a source with multiple elements" in supervised {
    val f = Flow.fromValues("foo", "bar")
    f.intersperse(", ").runToList shouldBe List("foo", ", ", "bar")
  }

  it should "intersperse with start, inject and end over an empty source" in supervised {
    val f = Flow.empty[String]
    f.intersperse("[", ", ", "]").runToList shouldBe List("[", "]")
  }

  it should "intersperse with start, inject and end over a source with one element" in supervised {
    val f = Flow.fromValues("foo")
    f.intersperse("[", ", ", "]").runToList shouldBe List("[", "foo", "]")
  }

  it should "intersperse with start, inject and end over a source with multiple elements" in supervised {
    val f = Flow.fromValues("foo", "bar")
    f.intersperse("[", ", ", "]").runToList shouldBe List("[", "foo", ", ", "bar", "]")
  }
