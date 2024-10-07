package ox.flow

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.*

class FlowOpsMapUsingSinkTest extends AnyFlatSpec with Matchers:

  behavior of "mapUsingSink"

  it should "map over a source, using emit" in:
    val c = Flow.fromValues(1, 2, 3)
    val s = c.mapUsingEmit(v =>
      emit =>
        emit(v + 1)
        emit(v * 10)
    )
    s.runToList() shouldBe List(2, 10, 3, 20, 4, 30)

  it should "propagate errors" in:
    val c = Flow.fromValues(1, 2, 3)
    val s = c.mapUsingEmit(_ => _ => throw new IllegalStateException)
    intercept[IllegalStateException](s.runToList())
end FlowOpsMapUsingSinkTest
