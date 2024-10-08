package ox.flow

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ox.*
import java.util.concurrent.atomic.AtomicBoolean

class FlowOpsEnsureTest extends AnyFlatSpec with Matchers:
  behavior of "ensure"

  it should "run in case of success" in:
    val didRun = new AtomicBoolean(false)
    val f = Flow.fromValues(1, 2, 3).ensure(didRun.set(true))

    didRun.get() shouldBe false
    f.runDrain()
    didRun.get() shouldBe true

  it should "run in case of error" in:
    val didRun = new AtomicBoolean(false)
    val f = Flow.fromValues(1, 2, 3).concat(Flow.failed(new RuntimeException)).ensure(didRun.set(true))

    didRun.get() shouldBe false
    intercept[RuntimeException]:
      f.runDrain()
    didRun.get() shouldBe true
end FlowOpsEnsureTest
