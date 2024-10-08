package ox.flow

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.*
import java.util.concurrent.atomic.AtomicInteger

class FlowOpsDrainTest extends AnyFlatSpec with Matchers:
  behavior of "drain"

  it should "drain all elements" in:
    val f = Flow.fromValues(1, 2, 3)
    f.drain().runToList() shouldBe List.empty

  it should "run any side-effects that are part of the flow" in:
    val c = new AtomicInteger(0)
    val f = Flow.fromValues(1, 2, 3).tap(_ => c.incrementAndGet().discard)
    f.drain().runDrain()
    c.get() shouldBe 3

  it should "merge with another flow" in:
    val c = new AtomicInteger(0)
    val f1 = Flow.fromValues(1, 2, 3).tap(_ => c.incrementAndGet().discard)
    val f2 = Flow.fromValues(4, 5, 6).tap(_ => c.incrementAndGet().discard)
    f1.drain().merge(f2).runToList() shouldBe List(4, 5, 6)
    c.get() shouldBe 6
end FlowOpsDrainTest
