package ox.flow

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class FlowOpsMapWithResourceTest extends AnyFlatSpec with Matchers:

  class TestCloseable extends AutoCloseable:
    var closed = false
    def close(): Unit = closed = true
    def transform(t: Int): String = t.toString

  behavior of "mapWithResource"

  it should "map elements using an acquired resource" in:
    var released = false
    val flow = Flow
      .fromValues(1, 2, 3)
      .mapWithResource(
        create = new StringBuilder,
        close = sb =>
          released = true; None
      )((sb, t) =>
        sb.append(t); sb.length()
      )
    flow.runToList() shouldBe List(1, 2, 3)
    released shouldBe true

  it should "emit a final element from close on normal completion" in:
    val flow = Flow
      .fromValues(1, 2, 3)
      .mapWithResource(
        create = new StringBuilder,
        close = sb => Some(sb.toString)
      )((sb, t) =>
        sb.append(t); t * 10
      )
    flow.runToList() shouldBe List(10, 20, 30, "123")

  it should "call close on upstream error" in:
    var closed = false
    val flow = Flow
      .fromValues(1, 2, 3)
      .map(t => if t == 2 then throw new RuntimeException("boom") else t)
      .mapWithResource(
        create = "resource",
        close = _ =>
          closed = true; None
      )((_, t) => t.toString)

    the[RuntimeException] thrownBy flow.runToList() should have message "boom"
    closed shouldBe true

  it should "drop close return value on error" in:
    val flow = Flow
      .fromValues(1, 2, 3)
      .map(t => if t == 3 then throw new RuntimeException("boom") else t)
      .mapWithResource(
        create = "resource",
        close = _ => Some("final")
      )((_, t) => t)

    the[RuntimeException] thrownBy flow.runToList() should have message "boom"

  it should "add close exception as suppressed when upstream errors" in:
    val flow = Flow
      .fromValues(1, 2, 3)
      .map(t => if t == 2 then throw new RuntimeException("upstream") else t)
      .mapWithResource(
        create = "resource",
        close = _ => throw new RuntimeException("close")
      )((_, t) => t)

    val e = the[RuntimeException] thrownBy flow.runToList()
    e.getMessage shouldBe "upstream"
    e.getSuppressed should have length 1
    e.getSuppressed.head.getMessage shouldBe "close"

  it should "propagate close exception when no upstream error" in:
    val flow = Flow
      .fromValues(1, 2, 3)
      .mapWithResource(
        create = "resource",
        close = _ => throw new RuntimeException("close")
      )((_, t) => t)

    the[RuntimeException] thrownBy flow.runToList() should have message "close"

  it should "handle empty flow" in:
    var created = false
    var closed = false
    val flow = Flow
      .empty[Int]
      .mapWithResource(
        create =
          created = true;
          "resource"
        ,
        close = _ =>
          closed = true; None
      )((_, t) => t)
    flow.runToList() shouldBe List.empty
    created shouldBe true
    closed shouldBe true

  it should "propagate errors in the mapping function" in:
    var closed = false
    val flow = Flow
      .fromValues(1, 2, 3)
      .mapWithResource(
        create = "resource",
        close = _ =>
          closed = true; None
      )((_, t) => if t == 2 then throw new RuntimeException("map error") else t)

    the[RuntimeException] thrownBy flow.runToList() should have message "map error"
    closed shouldBe true

  it should "not call close if create throws" in:
    var closed = false
    val flow = Flow
      .fromValues(1, 2, 3)
      .mapWithResource(
        create = throw new RuntimeException("create failed"),
        close = (_: String) =>
          closed = true; None
      )((_, t) => t)

    the[RuntimeException] thrownBy flow.runToList() should have message "create failed"
    closed shouldBe false

  behavior of "mapWithCloseableResource"

  it should "call close on the AutoCloseable resource" in:
    val resource = new TestCloseable
    val flow = Flow
      .fromValues(1, 2, 3)
      .mapWithCloseableResource(resource)((r, t) => r.transform(t))
    flow.runToList() shouldBe List("1", "2", "3")
    resource.closed shouldBe true

  it should "call close on the AutoCloseable resource when mapping function errors" in:
    val resource = new TestCloseable
    val flow = Flow
      .fromValues(1, 2, 3)
      .mapWithCloseableResource(resource)((_, t) => if t == 2 then throw new RuntimeException("boom") else t)

    the[RuntimeException] thrownBy flow.runToList() should have message "boom"
    resource.closed shouldBe true
end FlowOpsMapWithResourceTest
