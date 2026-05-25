package ox.flow

import org.scalatest.concurrent.Eventually
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.*

import java.util.concurrent.atomic.AtomicInteger

class FlowOpsTapTest extends AnyFlatSpec with Matchers with Eventually:

  it should "tap over a flow" in:
    val sum = new AtomicInteger()
    Flow.fromValues(1, 2, 3).tap(v => sum.addAndGet(v).discard).runToList() shouldBe List(1, 2, 3)
    sum.get() shouldBe 6
