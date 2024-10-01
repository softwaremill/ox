package ox.flow

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.*
import scala.concurrent.duration.DurationInt

class FlowOpsMergeTest extends AnyFlatSpec with Matchers:
  behavior of "merge"

  it should "merge two simple flows" in:
    val c1 = Flow.fromValues(1, 2, 3)
    val c2 = Flow.fromValues(4, 5, 6)

    val s = c1.merge(c2)

    s.runToList().sorted shouldBe List(1, 2, 3, 4, 5, 6)

  it should "merge two async flows" in:
    val c1 = Flow.fromValues(1, 2, 3).async()
    val c2 = Flow.fromValues(4, 5, 6).async()

    val s = c1.merge(c2)

    s.runToList().sorted shouldBe List(1, 2, 3, 4, 5, 6)

  it should "merge with a tick flow" in:
    val c1 = Flow.tick(100.millis, 0).take(3)
    val c2 = Flow.fromValues(0, 1, 2, 3)

    val r = c1.merge(c2).runToList()
    r should contain inOrder (0, 1, 2, 3)
    r.takeRight(2) shouldBe List(0, 0)
end FlowOpsMergeTest
