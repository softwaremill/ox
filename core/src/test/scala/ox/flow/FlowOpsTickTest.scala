package ox.flow

import org.scalatest.concurrent.Eventually
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.*

import scala.concurrent.duration.DurationInt

class FlowOpsTickTest extends AnyFlatSpec with Matchers with Eventually:
  it should "tick regularly" in supervised:
    val start = System.currentTimeMillis()
    val c = Flow.tick(100.millis).runToChannel()
    c.receive() shouldBe ()
    (System.currentTimeMillis() - start) shouldBe >=(0L)
    (System.currentTimeMillis() - start) shouldBe <=(50L)

    c.receive() shouldBe ()
    (System.currentTimeMillis() - start) shouldBe >=(100L)
    (System.currentTimeMillis() - start) shouldBe <=(150L)

    c.receive() shouldBe ()
    (System.currentTimeMillis() - start) shouldBe >=(200L)
    (System.currentTimeMillis() - start) shouldBe <=(250L)

  it should "tick immediately in case of a slow consumer, and then resume normal " in supervised:
    val start = System.currentTimeMillis()
    val c = Flow.tick(100.millis).runToChannel()

    // simulating a slow consumer
    sleep(200.millis)
    c.receive() shouldBe () // a tick should be waiting
    (System.currentTimeMillis() - start) shouldBe >=(200L)
    (System.currentTimeMillis() - start) shouldBe <=(250L)

    c.receive() shouldBe () // and immediately another, as the interval between send-s has passed
    (System.currentTimeMillis() - start) shouldBe >=(200L)
    (System.currentTimeMillis() - start) shouldBe <=(250L)
end FlowOpsTickTest
