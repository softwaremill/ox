package ox.flow

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.*

class FlowOpsZipAllTest extends AnyFlatSpec with Matchers:
  behavior of "zipAll"

  it should "not emit any element when both flows are empty" in:
    val s = Flow.empty[Int]
    val other = Flow.empty[String]

    s.zipAll(other, -1, "foo").runToList() shouldBe List.empty

  it should "emit this element when other flow is empty" in:
    val s = Flow.fromValues(1)
    val other = Flow.empty[String]

    s.zipAll(other, -1, "foo").runToList() shouldBe List((1, "foo"))

  it should "emit other element when this flow is empty" in:
    val s = Flow.empty[Int]
    val other = Flow.fromValues("a")

    s.zipAll(other, -1, "foo").runToList() shouldBe List((-1, "a"))

  it should "emit matching elements when both flows are of the same size" in:
    val s = Flow.fromValues(1, 2)
    val other = Flow.fromValues("a", "b")

    s.zipAll(other, -1, "foo").runToList() shouldBe List((1, "a"), (2, "b"))

  it should "emit default for other flow if this flow is longer" in:
    val s = Flow.fromValues(1, 2, 3)
    val other = Flow.fromValues("a")

    s.zipAll(other, -1, "foo").runToList() shouldBe List((1, "a"), (2, "foo"), (3, "foo"))

  it should "emit default for this flow if other flow is longer" in:
    val s = Flow.fromValues(1)
    val other = Flow.fromValues("a", "b", "c")

    s.zipAll(other, -1, "foo").runToList() shouldBe List((1, "a"), (-1, "b"), (-1, "c"))
end FlowOpsZipAllTest
