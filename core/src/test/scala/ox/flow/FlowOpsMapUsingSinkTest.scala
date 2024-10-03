package ox.flow

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.*

class FlowOpsMapUsingSinkTest extends AnyFlatSpec with Matchers:

  behavior of "mapUsingSink"

  it should "map over a source, using a sink" in:
    val c = Flow.fromValues(1, 2, 3)
    val s = c.mapUsingSink(v =>
      sink =>
        sink(v + 1)
        sink(v * 10)
    )
    s.runToList() shouldBe List(2, 10, 3, 20, 4, 30)

  it should "propagate errors" in:
    val c = Flow.fromValues(1, 2, 3)
    val s = c.mapUsingSink(_ => _ => throw new IllegalStateException)
    intercept[IllegalStateException](s.runToList())
end FlowOpsMapUsingSinkTest
